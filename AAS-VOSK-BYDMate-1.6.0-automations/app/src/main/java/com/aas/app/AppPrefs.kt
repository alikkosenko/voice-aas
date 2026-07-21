package com.aas.app

import android.content.Context
import android.content.SharedPreferences
import com.aas.app.ai.OpenAiCommandPlanner
import com.aas.app.ai.SecureApiKeyStore

/** Preferences; voice switch/key names intentionally match BYDMate. */
class AppPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val secureApiKeyStore = SecureApiKeyStore(appContext)

    init {
        migrateLegacySettings()
        migrateTts142()
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var activationKeyCode: Int
        get() = prefs.getInt(KEY_ACTIVATION_KEY, DEFAULT_ACTIVATION_KEY)
        set(value) = prefs.edit().putInt(KEY_ACTIVATION_KEY, value).apply()

    var captureNextKey: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_NEXT_KEY, false)
        set(value) = prefs.edit().putBoolean(KEY_CAPTURE_NEXT_KEY, value).apply()

    var nativeAssistantDisabled: Boolean
        get() = prefs.getBoolean(KEY_DISABLE_NATIVE_ASSISTANT, false)
        set(value) = prefs.edit().putBoolean(KEY_DISABLE_NATIVE_ASSISTANT, value).apply()

    var vehicleWritesEnabled: Boolean
        get() = prefs.getBoolean(KEY_VEHICLE_WRITES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VEHICLE_WRITES_ENABLED, value).apply()

    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE, "ru-RU") ?: "ru-RU"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** Enables short spoken summaries after command execution. */
    var voiceResponsesEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_RESPONSES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_RESPONSES_ENABLED, value).apply()

    /** Empty string means automatic selection with RHVoice preferred. */
    var ttsEnginePackage: String
        get() = prefs.getString(KEY_TTS_ENGINE_PACKAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE_PACKAGE, value.trim()).apply()

    /** Android TextToSpeech speech-rate multiplier. */
    var ttsSpeechRate: Float
        get() = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.05f).coerceIn(0.75f, 1.35f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, value.coerceIn(0.75f, 1.35f)).apply()

    var musicPackage: String
        get() = prefs.getString(KEY_MUSIC_PACKAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MUSIC_PACKAGE, value).apply()

    var navigationPackage: String
        get() = prefs.getString(KEY_NAVIGATION_PACKAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAVIGATION_PACKAGE, value).apply()

    var youtubePackage: String
        get() = prefs.getString(KEY_YOUTUBE_PACKAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YOUTUBE_PACKAGE, value).apply()

    var radioPackage: String
        get() = prefs.getString(KEY_RADIO_PACKAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RADIO_PACKAGE, value).apply()

    /** Number of user-visible heat/vent stages supported by this vehicle UI. */
    var seatMaxLevel: Int
        get() = prefs.getInt(KEY_SEAT_MAX_LEVEL, 3).let { if (it >= 5) 5 else 3 }
        set(value) = prefs.edit().putInt(KEY_SEAT_MAX_LEVEL, if (value >= 5) 5 else 3).apply()

    var aiCommandsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_COMMANDS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_COMMANDS_ENABLED, value).apply()

    var openAiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, OpenAiCommandPlanner.DEFAULT_MODEL)
            ?: OpenAiCommandPlanner.DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value.trim()).apply()

    fun saveOpenAiApiKey(value: String) = secureApiKeyStore.save(value)
    fun getOpenAiApiKey(): String? = secureApiKeyStore.load()
    fun hasOpenAiApiKey(): Boolean = secureApiKeyStore.hasKey()
    fun clearOpenAiApiKey() = secureApiKeyStore.clear()

    var authServerUrl: String
        get() = prefs.getString(KEY_AUTH_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_SERVER_URL, value.trim()).apply()

    var lastTranscript: String
        get() = prefs.getString(KEY_LAST_TRANSCRIPT, "—") ?: "—"
        set(value) = prefs.edit().putString(KEY_LAST_TRANSCRIPT, value).apply()

    var lastResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "—") ?: "—"
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    fun sharedPreferences(): SharedPreferences = prefs

    private fun migrateTts142() {
        if (prefs.getBoolean(KEY_TTS_142_MIGRATED, false)) return
        // 1.3.4–1.4.1 forcibly disabled speech, so an upgrade should restore the
        // new user-controllable default once. Future choices are preserved.
        prefs.edit()
            .putBoolean(KEY_VOICE_RESPONSES_ENABLED, true)
            .putBoolean(KEY_TTS_142_MIGRATED, true)
            .apply()
    }

    private fun migrateLegacySettings() {
        val legacy = appContext.getSharedPreferences(LEGACY_FILE_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val edit = prefs.edit()
        if (!prefs.contains(KEY_ENABLED) && legacy.contains("enabled")) {
            edit.putBoolean(KEY_ENABLED, legacy.getBoolean("enabled", true))
        }
        if (!prefs.contains(KEY_ACTIVATION_KEY) && legacy.contains("activation_key")) {
            edit.putInt(KEY_ACTIVATION_KEY, legacy.getInt("activation_key", DEFAULT_ACTIVATION_KEY))
        }
        val mappings = mapOf(
            KEY_VEHICLE_WRITES_ENABLED to "vehicle_writes_enabled",
            KEY_LANGUAGE to "language",
            KEY_VOICE_RESPONSES_ENABLED to "voice_responses_enabled",
            KEY_MUSIC_PACKAGE to "music_package",
            KEY_NAVIGATION_PACKAGE to "navigation_package",
            KEY_YOUTUBE_PACKAGE to "youtube_package",
            KEY_RADIO_PACKAGE to "radio_package",
            KEY_LAST_TRANSCRIPT to "last_transcript",
            KEY_LAST_RESULT to "last_result",
        )
        mappings.forEach { (newKey, oldKey) ->
            if (!prefs.contains(newKey) && legacy.contains(oldKey)) {
                when (val value = legacy.all[oldKey]) {
                    is String -> edit.putString(newKey, value)
                    is Boolean -> edit.putBoolean(newKey, value)
                    is Int -> edit.putInt(newKey, value)
                }
            }
        }
        edit.apply()
    }

    companion object {
        const val FILE_NAME = "voice"
        const val LEGACY_FILE_NAME = "aas_prefs"
        const val DEFAULT_ACTIVATION_KEY = 320

        const val KEY_ENABLED = "voice_enabled"
        const val KEY_ACTIVATION_KEY = "voice_keycode"
        const val KEY_CAPTURE_NEXT_KEY = "capture_next_key"
        const val KEY_DISABLE_NATIVE_ASSISTANT = "disable_native_assistant"
        const val KEY_VEHICLE_WRITES_ENABLED = "vehicle_writes_enabled"
        const val KEY_LANGUAGE = "language"
        const val KEY_VOICE_RESPONSES_ENABLED = "voice_responses_enabled"
        const val KEY_TTS_ENGINE_PACKAGE = "tts_engine_package"
        const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_142_MIGRATED = "tts_142_migrated"
        const val KEY_MUSIC_PACKAGE = "music_package"
        const val KEY_NAVIGATION_PACKAGE = "navigation_package"
        const val KEY_YOUTUBE_PACKAGE = "youtube_package"
        const val KEY_RADIO_PACKAGE = "radio_package"
        const val KEY_SEAT_MAX_LEVEL = "seat_max_level"
        const val KEY_AUTH_SERVER_URL = "auth_server_url"
        const val KEY_AI_COMMANDS_ENABLED = "ai_commands_enabled"
        const val KEY_OPENAI_MODEL = "openai_model"
        const val KEY_LAST_TRANSCRIPT = "last_transcript"
        const val KEY_LAST_RESULT = "last_result"
    }
}
