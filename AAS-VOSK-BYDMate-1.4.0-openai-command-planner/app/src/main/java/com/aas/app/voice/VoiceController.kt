package com.aas.app.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aas.app.AppPrefs
import com.aas.app.R
import com.aas.app.UkrainianTranslator
import com.aas.app.accessibility.AasAccessibilityService
import com.aas.app.ai.OpenAiCommandPlanner
import com.aas.app.commands.CommandDispatcher
import com.aas.app.commands.CommandParser
import com.aas.app.commands.ExecutionResult
import com.aas.app.commands.VoiceCommand
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One-shot voice controller powered by offline Vosk recognition.
 *
 * Recognition remains local. When AI command mode is enabled, only the final
 * transcript is sent to OpenAI to produce an allowlisted ordered command plan.
 */
class VoiceController(
    context: Context,
    private val prefs: AppPrefs,
    private val dispatcher: CommandDispatcher,
    private val aiPlanner: OpenAiCommandPlanner,
    private val onStateChanged: (String) -> Unit,
    private val onFinished: () -> Unit
) : RecognitionListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val parser = CommandParser()

    @Volatile private var model: Model? = null
    @Volatile private var loadedLanguageTag: String? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var state = State.IDLE
    private var sessionGeneration = 0L
    private var lastPartial = ""
    private var listenAfterPreload = false
    @Volatile private var destroyed = false

    private val timeout = Runnable {
        if (state != State.LISTENING) return@Runnable
        val partial = lastPartial.trim()
        AasAccessibilityService.showRecognizingOverlay()
        stopSpeechService(release = true)
        if (partial.isNotBlank()) {
            processTranscript(partial)
        } else {
            finishWith(t(R.string.command_not_heard), speak = false)
        }
    }


    fun preload() {
        mainHandler.post {
            if (destroyed || state != State.IDLE) return@post
            if (model != null && loadedLanguageTag == prefs.languageTag) {
                prefs.lastResult = t(R.string.vosk_ready)
                onStateChanged(t(R.string.state_ready))
                return@post
            }
            sessionGeneration += 1
            val generation = sessionGeneration
            state = State.PRELOADING_MODEL
            prefs.lastResult = t(R.string.vosk_loading, languageName(prefs.languageTag))
            onStateChanged(t(R.string.state_loading_model))

            ensureModel(prefs.languageTag, generation) {
                if (destroyed || generation != sessionGeneration || state != State.PRELOADING_MODEL) {
                    return@ensureModel
                }
                if (listenAfterPreload) {
                    listenAfterPreload = false
                    state = State.LOADING_MODEL
                    startVoskListening()
                } else {
                    state = State.IDLE
                    prefs.lastResult = t(R.string.vosk_ready)
                    onStateChanged(t(R.string.state_ready))
                }
            }
        }
    }


    fun isModelReady(): Boolean =
        model != null && loadedLanguageTag == prefs.languageTag &&
            state != State.PRELOADING_MODEL && state != State.LOADING_MODEL

    fun toggle() {
        // Show feedback before queueing any model/audio work. Calls from the
        // accessibility key callback run on the main thread, so this reveals
        // the pre-attached overlay in the same input event.
        if (state == State.IDLE || state == State.PRELOADING_MODEL) {
            AasAccessibilityService.showPreparingOverlay()
        }
        mainHandler.postAtFrontOfQueue {
            when (state) {
                State.IDLE -> listenOnceInternal()
                State.PRELOADING_MODEL -> {
                    AasAccessibilityService.showPreparingOverlay()
                    listenAfterPreload = true
                    prefs.lastResult = t(R.string.vosk_waiting_for_model)
                    onStateChanged(t(R.string.state_loading_model))
                }
                else -> cancelInternal()
            }
        }
    }

    fun listenOnce() {
        mainHandler.postAtFrontOfQueue { listenOnceInternal() }
    }

    private fun listenOnceInternal() {
        if (destroyed) return
        AasAccessibilityService.showPreparingOverlay()
        if (state == State.PRELOADING_MODEL) {
            listenAfterPreload = true
            return
        }
        if (state != State.IDLE) return
        sessionGeneration += 1
        val generation = sessionGeneration
        lastPartial = ""
        state = State.LOADING_MODEL
        prefs.lastTranscript = t(R.string.preparing_offline_model)
        prefs.lastResult = t(R.string.vosk_loading, languageName(prefs.languageTag))
        onStateChanged(t(R.string.state_loading_model))

        ensureModel(prefs.languageTag, generation) {
            if (destroyed || generation != sessionGeneration || state != State.LOADING_MODEL) {
                return@ensureModel
            }
            startVoskListening()
        }
    }

    fun cancel() {
        mainHandler.postAtFrontOfQueue { cancelInternal() }
    }

    private fun cancelInternal() {
        sessionGeneration += 1
        listenAfterPreload = false
        mainHandler.removeCallbacks(timeout)
        stopSpeechService(release = true)
        AasAccessibilityService.hideListeningOverlay()
        state = State.IDLE
        prefs.lastResult = t(R.string.listening_stopped)
        onStateChanged(t(R.string.state_stopped))
        onFinished()
    }

    fun destroy() {
        destroyed = true
        sessionGeneration += 1
        commandExecutor.shutdownNow()
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
            releaseSpeechResources()
            runCatching { model?.close() }
            model = null
            loadedLanguageTag = null
            state = State.IDLE
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        mainHandler.post {
            if (state != State.LISTENING) return@post
            val text = extractJsonText(hypothesis, "partial")
            if (text.isNotBlank()) {
                lastPartial = text
                prefs.lastTranscript = text
                onStateChanged(t(R.string.state_hearing))
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        mainHandler.post {
            if (state != State.LISTENING) return@post
            val text = extractJsonText(hypothesis, "text")
            if (text.isBlank()) return@post
            AasAccessibilityService.showRecognizingOverlay()
            stopSpeechService(release = true)
            processTranscript(text)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        mainHandler.post {
            if (state != State.LISTENING) return@post
            val text = extractJsonText(hypothesis, "text").ifBlank { lastPartial }
            AasAccessibilityService.showRecognizingOverlay()
            stopSpeechService(release = true)
            if (text.isBlank()) finishWith(t(R.string.speech_not_recognized)) else processTranscript(text)
        }
    }

    override fun onError(exception: Exception?) {
        mainHandler.post {
            if (state == State.IDLE || destroyed) return@post
            stopSpeechService(release = true)
            finishWith(t(R.string.vosk_error, exception?.message ?: t(R.string.unknown_error)))
        }
    }

    override fun onTimeout() {
        mainHandler.post { timeout.run() }
    }

    private fun ensureModel(languageTag: String, generation: Long, onReady: () -> Unit) {
        if (model != null && loadedLanguageTag == languageTag) {
            onReady()
            return
        }

        releaseSpeechResources()
        runCatching { model?.close() }
        model = null
        loadedLanguageTag = null

        val spec = modelSpec(languageTag)
        StorageService.unpack(
            appContext,
            spec.assetPath,
            spec.storageName,
            { unpackedModel ->
                mainHandler.post {
                    if (destroyed || generation != sessionGeneration) {
                        runCatching { unpackedModel.close() }
                        return@post
                    }
                    model = unpackedModel
                    loadedLanguageTag = languageTag
                    prepareRecognizer()
                    onReady()
                }
            },
            { error: IOException ->
                mainHandler.post {
                    if (destroyed || generation != sessionGeneration) return@post
                    state = State.IDLE
                    finishWith(
                        if (prefs.languageTag.startsWith("uk"))
                            "Не вдалося розпакувати модель Vosk (${languageName(languageTag)}): ${error.message}"
                        else
                            "Не удалось распаковать модель Vosk (${languageName(languageTag)}): ${error.message}",
                        speak = false
                    )
                }
            }
        )
    }

    private fun startVoskListening() {
        val activeModel = model
        if (activeModel == null) {
            finishWith(t(R.string.model_not_loaded), speak = false)
            return
        }

        try {
            prepareRecognizer()
            recognizer?.reset()
            // SpeechService owns AudioRecord. Creating it during application
            // preload can crash some DiLink builds before the microphone is
            // actually requested. Keep only the expensive Vosk Recognizer
            // warm and create the lightweight audio service on demand.
            stopSpeechService(release = true)
            val activeRecognizer = recognizer ?: throw IOException("Recognizer not prepared")
            val service = SpeechService(activeRecognizer, SAMPLE_RATE).also { speechService = it }
            state = State.LISTENING
            prefs.lastTranscript = t(R.string.listening)
            prefs.lastResult = t(R.string.vosk_offline)
            onStateChanged(t(R.string.state_speak))
            AasAccessibilityService.showListeningOverlay()
            service.startListening(this)
            beep()
            mainHandler.removeCallbacks(timeout)
            mainHandler.postDelayed(timeout, LISTEN_TIMEOUT_MS)
        } catch (error: Exception) {
            stopSpeechService(release = true)
            finishWith(t(R.string.vosk_start_failed, error.message ?: t(R.string.unknown_error)), speak = false)
        }
    }

    private fun processTranscript(transcript: String) {
        mainHandler.removeCallbacks(timeout)
        if (state == State.PROCESSING) return

        val normalizedTranscript = transcript.trim()
        if (normalizedTranscript.isBlank()) {
            finishWith(t(R.string.empty_recognition))
            return
        }

        prefs.lastTranscript = normalizedTranscript
        state = State.PROCESSING
        AasAccessibilityService.showExecutingOverlay()
        onStateChanged(t(R.string.state_executing))
        val generation = sessionGeneration

        commandExecutor.execute {
            val result = buildAndExecutePlan(normalizedTranscript)

            mainHandler.post {
                if (destroyed || generation != sessionGeneration || state != State.PROCESSING) {
                    return@post
                }
                val technical = localizeResult(result.technicalMessage)
                val spoken = localizeResult(result.spokenMessage)
                prefs.lastResult = technical
                speakResult(spoken, result.success)
                state = State.IDLE
                AasAccessibilityService.hideListeningOverlay()
                onStateChanged(if (result.success) t(R.string.state_ready) else t(R.string.state_failed))
                onFinished()
            }
        }
    }


    private fun buildAndExecutePlan(transcript: String): ExecutionResult {
        val localCommand = { parser.parse(transcript) }
        var plannerTechnical = "local parser"

        val commands: List<VoiceCommand> = if (aiPlanner.isConfigured()) {
            try {
                val plan = aiPlanner.plan(transcript)
                plannerTechnical = plan.technicalMessage
                if (plan.commands.isNotEmpty()) plan.commands
                else listOfNotNull(localCommand()).also {
                    plannerTechnical += "; empty AI plan -> local fallback=${it.isNotEmpty()}"
                }
            } catch (error: Exception) {
                listOfNotNull(localCommand()).also {
                    plannerTechnical = "AI failed: ${error.message}; local fallback=${it.isNotEmpty()}"
                }
            }
        } else {
            listOfNotNull(localCommand())
        }

        if (commands.isEmpty()) {
            return ExecutionResult(
                success = false,
                spokenMessage = t(R.string.unsupported_command),
                technicalMessage = "${t(R.string.unsupported_command)}; $plannerTechnical"
            )
        }

        val results = mutableListOf<ExecutionResult>()
        for ((index, command) in commands.withIndex()) {
            val result = runCatching { dispatcher.execute(command) }
                .getOrElse { ExecutionResult(false, t(R.string.execution_error), it.stackTraceToString()) }
            results += result
            if (index < commands.lastIndex) Thread.sleep(COMMAND_GAP_MS)
        }

        val allSucceeded = results.all { it.success }
        val completed = results.count { it.success }
        val summary = if (commands.size == 1) {
            results.first().spokenMessage
        } else if (allSucceeded) {
            "Выполнено команд: ${commands.size}"
        } else {
            "Выполнено $completed из ${commands.size} команд"
        }
        val technical = buildString {
            append(plannerTechnical)
            results.forEachIndexed { index, item ->
                append("\n#")
                append(index + 1)
                append(' ')
                append(commands[index]::class.simpleName ?: commands[index].toString())
                append(": success=")
                append(item.success)
                append("; ")
                append(item.technicalMessage)
            }
        }
        return ExecutionResult(allSucceeded, summary, technical)
    }

    private fun finishWith(message: String, speak: Boolean = true) {
        mainHandler.removeCallbacks(timeout)
        stopSpeechService(release = true)
        val localized = localizeResult(message)
        prefs.lastResult = localized
        if (speak) speakResult(localized, success = false)
        AasAccessibilityService.hideListeningOverlay()
        state = State.IDLE
        onStateChanged(t(R.string.state_ready))
        onFinished()
    }

    private fun prepareRecognizer() {
        if (recognizer != null) return
        val activeModel = model ?: return
        recognizer = Recognizer(activeModel, SAMPLE_RATE)
    }

    private fun stopSpeechService(release: Boolean = false) {
        val service = speechService ?: return
        runCatching { service.stop() }
        if (release) {
            runCatching { service.shutdown() }
            speechService = null
        }
    }

    private fun releaseSpeechResources() {
        AasAccessibilityService.hideListeningOverlay()
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    /** Voice replies remain intentionally disabled in 1.4.0. */
    fun setVoiceResponsesEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        prefs.voiceResponsesEnabled = false
    }

    fun reinitializeTts() = Unit
    fun testSpeech() = Unit
    fun stopSpeaking() = Unit
    private fun speak(@Suppress("UNUSED_PARAMETER") text: String) = Unit
    private fun speakResult(
        @Suppress("UNUSED_PARAMETER") text: String,
        @Suppress("UNUSED_PARAMETER") success: Boolean
    ) = Unit

    private fun beep() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                mainHandler.postDelayed({ release() }, 220L)
            }
        }
    }

    private fun extractJsonText(json: String?, field: String): String = runCatching {
        JSONObject(json.orEmpty()).optString(field).trim()
    }.getOrDefault("")


    private fun t(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    private fun localizeResult(text: String): String =
        if (prefs.languageTag.startsWith("uk", ignoreCase = true)) UkrainianTranslator.translate(text) else text

    private fun modelSpec(languageTag: String): ModelSpec = when {
        languageTag.startsWith("uk", ignoreCase = true) ->
            ModelSpec("vosk/uk", "aas-vosk-uk")
        else ->
            ModelSpec("vosk/ru", "aas-vosk-ru")
    }

    private fun languageName(languageTag: String): String =
        if (languageTag.startsWith("uk", ignoreCase = true)) t(R.string.language_uk_model) else t(R.string.language_ru_model)

    private data class ModelSpec(val assetPath: String, val storageName: String)

    private enum class State { IDLE, PRELOADING_MODEL, LOADING_MODEL, LISTENING, PROCESSING }

    companion object {
        private const val TAG = "AasVoiceController"
        private const val SAMPLE_RATE = 16_000.0f
        private const val LISTEN_TIMEOUT_MS = 30_000L
        private const val COMMAND_GAP_MS = 220L
    }
}
