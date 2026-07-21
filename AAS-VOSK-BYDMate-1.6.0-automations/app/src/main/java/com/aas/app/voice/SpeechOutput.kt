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
 * Small, process-wide Android TTS controller.
 *
 * AAS does not bundle a neural voice model. It uses an installed Android TTS service,
 * preferring RHVoice for low latency and compact offline Russian/Ukrainian voices.
 * All normal engines are routed through the public media/navigation audio path; the
 * private BYD stream 17 is deliberately not used because it is silent on some DiLink
 * releases even when TextToSpeech reports successful playback.
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
    private var generation = 0L
    private var ready = false
    private var initializing = false
    private var enabled = prefs.voiceResponsesEnabled

    private var pendingText: String? = null
    private var pendingSuccess: Boolean? = null
    private var activeText: String? = null
    private var activeSuccess: Boolean? = null
    private var activeUtteranceId: String? = null
    private var utteranceStarted = false
    private var watchdog: Runnable? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        mainHandler.post { initialize(force = false) }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        runOnMain {
            if (value) initialize(force = false)
            else {
                pendingText = null
                pendingSuccess = null
                stopNow()
            }
        }
    }

    fun reinitialize() = runOnMain { initialize(force = true) }

    fun speak(text: String) = enqueue(text, success = null)

    /** Speaks the full localized command result, not merely a generic acknowledgement. */
    fun speakResult(text: String, success: Boolean) = enqueue(text, success)

    fun stop() = runOnMain { stopNow() }

    fun shutdown() = runOnMain {
        enabled = false
        pendingText = null
        pendingSuccess = null
        stopNow()
        bundledAck.shutdown()
        shutdownEngine()
    }

    private fun enqueue(text: String, success: Boolean?) {
        if (!enabled || !prefs.voiceResponsesEnabled || text.isBlank()) return
        runOnMain {
            if (!enabled || !prefs.voiceResponsesEnabled) return@runOnMain
            pendingText = text.trim()
            pendingSuccess = success
            if (ready) flushPending() else initialize(force = false)
        }
    }

    private fun initialize(force: Boolean) {
        if (!enabled || !prefs.voiceResponsesEnabled) return
        if (!force && (ready || initializing)) {
            if (ready) flushPending()
            return
        }
        candidates = discoverCandidates()
        candidateIndex = 0
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

        fun packageInstalled(packageName: String): Boolean = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)

        val selected = prefs.ttsEnginePackage.trim().takeIf { it.isNotEmpty() }
        val knownInstalled = listOf(
            RHVOICE_PACKAGE,
            GOOGLE_TTS_PACKAGE,
            PICO_TTS_PACKAGE,
            SAMSUNG_TTS_PACKAGE,
            BYD_TTS_PACKAGE,
            BYD_TTS_ENGINE_PACKAGE,
        ).filter(::packageInstalled)
        val installed = (installedServices + knownInstalled).distinct()

        return buildList<String?> {
            if (selected != null && (selected in installed || packageInstalled(selected))) add(selected)
            if (RHVOICE_PACKAGE in installed) add(RHVOICE_PACKAGE)
            // The system default is a useful fallback, but comes after RHVoice because
            // a number of DiLink images default to a vendor engine without RU/UK data.
            add(null)
            listOf(GOOGLE_TTS_PACKAGE, PICO_TTS_PACKAGE, SAMSUNG_TTS_PACKAGE)
                .filter { it in installed }
                .forEach(::add)
            installed.filterNot { it in this || it in BYD_ENGINE_PACKAGES }.forEach(::add)
            // BYD engines remain last-resort candidates. They still use the public
            // media path, never the private stream 17.
            installed.filter { it in BYD_ENGINE_PACKAGES }.forEach(::add)
        }.distinct()
    }

    private fun createEngine() {
        if (!enabled || candidateIndex !in candidates.indices) {
            failPermanently("no candidates")
            return
        }
        initializing = true
        ready = false
        val myGeneration = ++generation
        val packageName = candidates[candidateIndex]
        activeEnginePackage = packageName
        var created: TextToSpeech? = null

        val listener = TextToSpeech.OnInitListener { status: Int ->
            mainHandler.post {
                if (myGeneration != generation || created == null || tts !== created) {
                    runCatching { created?.shutdown() }
                    return@post
                }
                initializing = false
                if (status != TextToSpeech.SUCCESS) {
                    tryNextEngine("init status=$status")
                    return@post
                }
                if (!configureEngine(created!!)) {
                    tryNextEngine("language unavailable")
                    return@post
                }
                installProgressListener(created!!)
                ready = true
                Log.i(TAG, "TTS ready engine=${packageName ?: "system-default"}")
                flushPending()
            }
        }

        created = try {
            if (packageName == null) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, packageName)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to construct engine=${packageName ?: "system-default"}", error)
            null
        }
        tts = created
        if (created == null) {
            initializing = false
            tryNextEngine("constructor failed")
        }
    }

    private fun configureEngine(engine: TextToSpeech): Boolean {
        val requested = Locale.forLanguageTag(prefs.languageTag)
        val candidates = buildList {
            add(requested)
            if (prefs.languageTag.startsWith("uk", ignoreCase = true)) {
                add(Locale("uk", "UA"))
                add(Locale("uk"))
                // A Russian fallback is preferable to silence when an installed
                // engine has no Ukrainian voice data.
                add(Locale("ru", "RU"))
            } else {
                add(Locale("ru", "RU"))
                add(Locale("ru"))
            }
        }.distinctBy { it.toLanguageTag() }

        val selectedLocale = candidates.firstOrNull { locale ->
            val availability = runCatching { engine.isLanguageAvailable(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            availability != TextToSpeech.LANG_MISSING_DATA &&
                availability != TextToSpeech.LANG_NOT_SUPPORTED &&
                runCatching { engine.setLanguage(locale) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
        }

        // Some vendor engines incorrectly report missing metadata. Permit only the
        // final fallback candidate in that case; normal engines must confirm support.
        val languageUsable = selectedLocale != null || candidateIndex == this.candidates.lastIndex
        runCatching { engine.setAudioAttributes(speechAttributes()) }
        runCatching { engine.setSpeechRate(prefs.ttsSpeechRate) }
        runCatching { engine.setPitch(1.0f) }
        return languageUsable
    }

    private fun installProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId != activeUtteranceId) return@post
                    utteranceStarted = true
                    cancelWatchdog()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId == activeUtteranceId) finishUtterance()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    if (utteranceId == activeUtteranceId) retryUtterance("playback error=$errorCode")
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
        val success = pendingSuccess
        pendingText = null
        pendingSuccess = null
        activeText = text
        activeSuccess = success
        utteranceStarted = false
        val id = "aas-tts-${System.nanoTime()}"
        activeUtteranceId = id

        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
        acquireAudioFocus()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        }.getOrDefault(TextToSpeech.ERROR)

        if (result != TextToSpeech.SUCCESS) {
            retryUtterance("speak rejected")
            return
        }
        watchdog = Runnable {
            if (activeUtteranceId == id && !utteranceStarted) retryUtterance("utterance did not start")
        }.also { mainHandler.postDelayed(it, START_TIMEOUT_MS) }
    }

    private fun retryUtterance(reason: String) {
        val text = activeText ?: pendingText
        val success = activeSuccess ?: pendingSuccess
        cancelWatchdog()
        runCatching { tts?.stop() }
        releaseAudioFocus()
        activeUtteranceId = null
        activeText = null
        activeSuccess = null
        if (text.isNullOrBlank()) return
        pendingText = text
        pendingSuccess = success
        tryNextEngine(reason)
    }

    private fun tryNextEngine(reason: String) {
        Log.w(TAG, "Trying next TTS engine: $reason")
        shutdownEngine()
        if (candidateIndex < candidates.lastIndex) {
            candidateIndex += 1
            createEngine()
        } else {
            failPermanently(reason)
        }
    }

    private fun failPermanently(reason: String) {
        ready = false
        initializing = false
        Log.w(TAG, "No usable TTS engine: $reason")
        val success = pendingSuccess
        pendingText = null
        pendingSuccess = null
        // Preserve a tiny offline audible acknowledgement as the last safety net.
        if (success != null && enabled && prefs.voiceResponsesEnabled) {
            bundledAck.play(success, prefs.languageTag)
        }
    }

    private fun finishUtterance() {
        cancelWatchdog()
        activeUtteranceId = null
        activeText = null
        activeSuccess = null
        utteranceStarted = false
        releaseAudioFocus()
    }

    private fun stopNow() {
        bundledAck.stop()
        cancelWatchdog()
        runCatching { tts?.stop() }
        activeUtteranceId = null
        activeText = null
        activeSuccess = null
        utteranceStarted = false
        releaseAudioFocus()
    }

    private fun shutdownEngine() {
        generation += 1
        ready = false
        initializing = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        activeEnginePackage = null
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun acquireAudioFocus() {
        releaseAudioFocus()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes())
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

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    companion object {
        private const val TAG = "AasSpeechOutput"
        private const val START_TIMEOUT_MS = 3_500L
        const val RHVOICE_PACKAGE = "com.github.olga_yakovleva.rhvoice.android"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        private const val PICO_TTS_PACKAGE = "com.svox.pico"
        private const val SAMSUNG_TTS_PACKAGE = "com.samsung.SMT"
        private const val BYD_TTS_PACKAGE = "com.byd.autovoice.tts"
        private const val BYD_TTS_ENGINE_PACKAGE = "com.byd.autovoice.engine"
        private val BYD_ENGINE_PACKAGES = setOf(BYD_TTS_PACKAGE, BYD_TTS_ENGINE_PACKAGE)
    }
}
