package com.aas.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aas.app.AppPrefs
import java.util.Locale

/**
 * Process-wide, fault-tolerant Android TTS output.
 *
 * Automotive Android builds often have several TTS services, a disabled BYD engine,
 * or an engine that initializes successfully but cannot speak Russian/Ukrainian.
 * This controller enumerates every installed TTS service, selects one that supports
 * the requested locale, routes speech through the media audio path, requests audio
 * focus and retries the utterance with the next engine when initialization/playback
 * fails or never starts.
 */
class SpeechOutput(
    context: Context,
    private val prefs: AppPrefs,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bundledAck = BundledAckPlayer(appContext)

    private var tts: TextToSpeech? = null
    private var activeEnginePackage: String? = null
    private var candidates: List<String?> = emptyList()
    private var candidateIndex = 0
    private var engineGeneration = 0L
    private var ready = false
    private var initializing = false
    private var enabled = prefs.voiceResponsesEnabled

    private var pendingText: String? = null
    private var activeText: String? = null
    private var activeUtteranceId: String? = null
    private var utteranceStarted = false
    private var utteranceFallbacks = 0
    private var startWatchdog: Runnable? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        mainHandler.post { initialize(force = false) }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        val action = Runnable {
            if (value) initialize(force = false)
            else {
                pendingText = null
                activeText = null
                stopNow()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    fun reinitialize() {
        val action = Runnable { initialize(force = true) }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    fun speak(text: String) {
        if (!enabled || !prefs.voiceResponsesEnabled || text.isBlank()) return
        val action = Runnable {
            if (!enabled || !prefs.voiceResponsesEnabled) return@Runnable
            pendingText = text.trim()
            utteranceFallbacks = 0
            if (ready) flushPending() else initialize(force = false)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    /**
     * Audible command-result path for DiLink. It does not depend on Android TTS:
     * a compact offline Russian/Ukrainian acknowledgement is bundled in the APK.
     * The detailed result remains visible in the overlay and diagnostics.
     */
    fun speakResult(text: String, success: Boolean) {
        if (!enabled || !prefs.voiceResponsesEnabled || text.isBlank()) return
        val action = Runnable {
            if (!enabled || !prefs.voiceResponsesEnabled) return@Runnable
            // Stop a possibly stuck/silent system utterance before the guaranteed clip.
            cancelStartWatchdog()
            runCatching { tts?.stop() }
            pendingText = null
            activeText = null
            activeUtteranceId = null
            releaseAudioFocus()
            bundledAck.play(success, prefs.languageTag)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    fun stop() {
        val action = Runnable { stopNow() }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    fun shutdown() {
        val action = Runnable {
            enabled = false
            pendingText = null
            activeText = null
            stopNow()
            bundledAck.shutdown()
            shutdownEngine()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    private fun initialize(force: Boolean) {
        if (!enabled || !prefs.voiceResponsesEnabled) return
        if (!force && (ready || initializing)) {
            if (ready) flushPending()
            return
        }
        if (force || candidates.isEmpty()) {
            candidates = discoverCandidates()
            candidateIndex = 0
        }
        shutdownEngine()
        createEngine()
    }

    private fun discoverCandidates(): List<String?> {
        val installedServices = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())

        // Some BYD builds hide the vendor service from queryIntentServices until it
        // has been explicitly enabled by the shell helper. Keep known packages in
        // the candidate list whenever the package itself exists.
        fun packageInstalled(packageName: String): Boolean = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)

        val installed = (installedServices + listOf(
            BYD_TTS_PACKAGE,
            BYD_TTS_ENGINE_PACKAGE,
        ).filter(::packageInstalled)).distinct()

        // On DiLink the BYD engine understands the custom voice stream and is the
        // most reliable first choice. Standard Android engines remain fallbacks.
        val preferred = listOfNotNull(
            BYD_TTS_PACKAGE.takeIf { it in installed },
            BYD_TTS_ENGINE_PACKAGE.takeIf { it in installed },
            "com.google.android.tts".takeIf { it in installed },
            "com.github.olga_yakovleva.rhvoice.android".takeIf { it in installed },
            "com.svox.pico".takeIf { it in installed },
            "com.samsung.SMT".takeIf { it in installed },
        )
        return (preferred + installed + listOf<String?>(null)).distinct()
    }

    private fun createEngine() {
        if (!enabled || initializing || candidateIndex !in candidates.indices) return
        initializing = true
        ready = false
        val generation = ++engineGeneration
        val packageName = candidates[candidateIndex]
        activeEnginePackage = packageName
        var created: TextToSpeech? = null

        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (generation != engineGeneration || created == null || tts !== created) {
                    runCatching { created?.shutdown() }
                    return@post
                }
                initializing = false
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS init failed engine=${packageName ?: "default"} status=$status")
                    tryNextEngine("init status=$status")
                    return@post
                }
                if (!configureEngine(created!!)) {
                    Log.w(TAG, "TTS language unavailable engine=${packageName ?: "default"}")
                    tryNextEngine("language unavailable")
                    return@post
                }
                installProgressListener(created!!)
                ready = true
                flushPending()
            }
        }

        created = runCatching {
            if (packageName == null) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, packageName)
        }.onFailure {
            initializing = false
            Log.w(TAG, "Unable to construct TTS engine=${packageName ?: "default"}", it)
        }.getOrNull()
        tts = created

        if (created == null) tryNextEngine("constructor failed")
    }

    private fun configureEngine(engine: TextToSpeech): Boolean {
        val requested = Locale.forLanguageTag(prefs.languageTag)
        val locales = buildList {
            add(requested)
            if (prefs.languageTag.startsWith("uk", ignoreCase = true)) {
                add(Locale("uk", "UA"))
                add(Locale("uk"))
                add(Locale("ru", "RU"))
            } else {
                add(Locale("ru", "RU"))
                add(Locale("ru"))
            }
        }.distinctBy { it.toLanguageTag() }

        val selected = locales.firstOrNull { locale ->
            val availability = runCatching { engine.isLanguageAvailable(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            availability != TextToSpeech.LANG_MISSING_DATA &&
                availability != TextToSpeech.LANG_NOT_SUPPORTED &&
                runCatching { engine.setLanguage(locale) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
        }

        // The final default engine is still allowed as a last-resort even if it
        // cannot report language metadata correctly (common on vendor engines).
        val languageUsable = selected != null || candidateIndex == candidates.lastIndex
        // BYD DiLink exposes its spoken-assistant slider as custom legacy stream
        // 17 (STREAM_BTTS). Routing standard Android TTS to STREAM_MUSIC can report
        // successful playback while remaining inaudible in the vehicle. The custom
        // route is used first; accessibility is a safe framework fallback.
        val attributes = outputAudioAttributes()
        val audioResult = runCatching { engine.setAudioAttributes(attributes) }
            .getOrDefault(TextToSpeech.ERROR)
        if (audioResult != TextToSpeech.SUCCESS) {
            runCatching { engine.setAudioAttributes(standardSpeechAttributes()) }
        }
        runCatching { engine.setSpeechRate(1.0f) }
        runCatching { engine.setPitch(1.0f) }
        return languageUsable
    }

    private fun installProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId != activeUtteranceId) return@post
                    utteranceStarted = true
                    cancelStartWatchdog()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId != activeUtteranceId) return@post
                    finishUtterance()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    if (utteranceId != activeUtteranceId) return@post
                    retryUtterance("playback error=$errorCode")
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mainHandler.post {
                    if (utteranceId == activeUtteranceId && !interrupted) finishUtterance()
                }
            }
        })
    }

    private fun flushPending() {
        if (!ready || !enabled || !prefs.voiceResponsesEnabled) return
        val text = pendingText?.takeIf { it.isNotBlank() } ?: return
        pendingText = null
        activeText = text
        utteranceStarted = false
        val id = "aas-result-${System.nanoTime()}"
        activeUtteranceId = id
        val outputStream = outputStreamType()
        prepareOutputStream(outputStream)
        acquireAudioFocus()

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, outputStream)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        }.getOrDefault(TextToSpeech.ERROR)

        if (result != TextToSpeech.SUCCESS) {
            retryUtterance("speak rejected")
            return
        }
        scheduleStartWatchdog(id)
    }

    private fun retryUtterance(reason: String) {
        val text = activeText ?: pendingText
        cancelStartWatchdog()
        releaseAudioFocus()
        activeUtteranceId = null
        activeText = null
        if (text.isNullOrBlank()) return

        utteranceFallbacks += 1
        if (utteranceFallbacks >= candidates.size) {
            Log.w(TAG, "TTS failed after all engines: $reason")
            pendingText = null
            return
        }
        pendingText = text
        tryNextEngine(reason)
    }

    private fun tryNextEngine(reason: String) {
        Log.w(TAG, "Trying next TTS engine: $reason")
        shutdownEngine()
        if (candidateIndex < candidates.lastIndex) {
            candidateIndex += 1
            createEngine()
        } else {
            ready = false
            initializing = false
            Log.w(TAG, "No usable TTS engine found")
        }
    }

    private fun scheduleStartWatchdog(id: String) {
        cancelStartWatchdog()
        startWatchdog = Runnable {
            if (activeUtteranceId == id && !utteranceStarted) {
                retryUtterance("utterance did not start")
            }
        }.also { mainHandler.postDelayed(it, START_TIMEOUT_MS) }
    }

    private fun cancelStartWatchdog() {
        startWatchdog?.let(mainHandler::removeCallbacks)
        startWatchdog = null
    }

    private fun finishUtterance() {
        cancelStartWatchdog()
        activeUtteranceId = null
        activeText = null
        utteranceStarted = false
        utteranceFallbacks = 0
        releaseAudioFocus()
    }

    private fun stopNow() {
        bundledAck.stop()
        cancelStartWatchdog()
        runCatching { tts?.stop() }
        activeUtteranceId = null
        activeText = null
        utteranceStarted = false
        releaseAudioFocus()
    }

    private fun shutdownEngine() {
        engineGeneration += 1
        ready = false
        initializing = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        activeEnginePackage = null
    }

    /**
     * BYD's own voice packages use the dedicated DiLink BTTS stream (17). Normal
     * Android engines must stay on STREAM_MUSIC: forcing Google/Pico/RHVoice onto
     * the vendor-only stream can produce successful callbacks with no audible sound.
     */
    private fun outputStreamType(): Int = if (activeEnginePackage in BYD_ENGINE_PACKAGES) {
        BYD_STREAM_BTTS
    } else {
        AudioManager.STREAM_MUSIC
    }

    private fun prepareOutputStream(stream: Int) {
        runCatching {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            val current = audioManager.getStreamVolume(stream)
            if (current <= 0) {
                audioManager.setStreamVolume(stream, (max / 3).coerceAtLeast(1), 0)
            }
        }.onFailure {
            Log.w(TAG, "Speech output stream $stream unavailable", it)
        }
    }

    private fun bydVoiceAttributes(): AudioAttributes? = runCatching {
        AudioAttributes.Builder().setLegacyStreamType(BYD_STREAM_BTTS).build()
    }.getOrNull()

    private fun standardSpeechAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun outputAudioAttributes(): AudioAttributes =
        if (outputStreamType() == BYD_STREAM_BTTS) {
            bydVoiceAttributes() ?: standardSpeechAttributes()
        } else {
            standardSpeechAttributes()
        }

    private fun acquireAudioFocus() {
        releaseAudioFocus()
        val attributes = outputAudioAttributes()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun releaseAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager.abandonAudioFocusRequest(request) }
    }

    companion object {
        private const val TAG = "AasSpeechOutput"
        private const val START_TIMEOUT_MS = 5_000L
        private const val BYD_STREAM_BTTS = 17
        private const val BYD_TTS_PACKAGE = "com.byd.autovoice.tts"
        private const val BYD_TTS_ENGINE_PACKAGE = "com.byd.autovoice.engine"
        private val BYD_ENGINE_PACKAGES = setOf(BYD_TTS_PACKAGE, BYD_TTS_ENGINE_PACKAGE)
    }
}
