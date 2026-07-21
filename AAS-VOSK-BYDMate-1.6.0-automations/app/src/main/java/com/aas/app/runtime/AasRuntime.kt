package com.aas.app.runtime

import android.content.Context
import com.aas.app.AppPrefs
import com.aas.app.adb.AdbKeyStore
import com.aas.app.adb.AdbOnDeviceClient
import com.aas.app.ai.OpenAiCommandPlanner
import com.aas.app.commands.CommandDispatcher
import com.aas.app.commands.TextCommandExecutor
import com.aas.app.automation.AutomationEngine
import com.aas.app.automation.AutomationRepository
import com.aas.app.helper.HelperBootstrap
import com.aas.app.helper.HelperClient
import com.aas.app.vehicle.BydVehicleAdapter
import com.aas.app.voice.VoiceController
import com.aas.app.voice.SpeechOutput

/** Process-wide objects. The Vosk model is loaded once and kept for the process lifetime. */
object AasRuntime {
    @Volatile private var initialized = false

    lateinit var prefs: AppPrefs
        private set
    lateinit var keyStore: AdbKeyStore
        private set
    lateinit var adb: AdbOnDeviceClient
        private set
    lateinit var helper: HelperClient
        private set
    lateinit var bootstrap: HelperBootstrap
        private set
    lateinit var vehicle: BydVehicleAdapter
        private set
    lateinit var dispatcher: CommandDispatcher
        private set
    lateinit var aiPlanner: OpenAiCommandPlanner
        private set
    lateinit var textCommandExecutor: TextCommandExecutor
        private set
    lateinit var speechOutput: SpeechOutput
        private set
    lateinit var automationRepository: AutomationRepository
        private set
    lateinit var automation: AutomationEngine
        private set
    lateinit var voice: VoiceController
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        prefs = AppPrefs(app)
        keyStore = AdbKeyStore(app)
        adb = AdbOnDeviceClient(app, keyStore)
        helper = HelperClient()
        bootstrap = HelperBootstrap(app, adb, helper) { keyStore.getFingerprint() }
        vehicle = BydVehicleAdapter(app, prefs, bootstrap, helper, adb)
        dispatcher = CommandDispatcher(app, vehicle)
        aiPlanner = OpenAiCommandPlanner(prefs)
        textCommandExecutor = TextCommandExecutor(app, prefs, dispatcher, aiPlanner)
        speechOutput = SpeechOutput(app, prefs)
        automationRepository = AutomationRepository(app)
        automation = AutomationEngine(
            context = app,
            prefs = prefs,
            repository = automationRepository,
            textExecutor = textCommandExecutor,
            speechOutput = speechOutput,
            vehicle = vehicle,
        )
        voice = VoiceController(
            context = app,
            prefs = prefs,
            textCommandExecutor = textCommandExecutor,
            speechOutput = speechOutput,
            onStateChanged = {},
            onFinished = {},
        )
        initialized = true
        // Automations do not depend on the microphone or Vosk. Keep the model
        // cold when the voice assistant is disabled so an automation-only setup
        // uses less RAM and starts faster.
        if (prefs.enabled) voice.preload()
    }

    fun requireInitialized(context: Context) {
        if (!initialized) initialize(context)
    }
}
