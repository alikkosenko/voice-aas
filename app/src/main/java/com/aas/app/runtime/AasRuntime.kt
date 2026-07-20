package com.aas.app.runtime

import android.content.Context
import com.aas.app.AppPrefs
import com.aas.app.adb.AdbKeyStore
import com.aas.app.adb.AdbOnDeviceClient
import com.aas.app.commands.CommandDispatcher
import com.aas.app.helper.HelperBootstrap
import com.aas.app.helper.HelperClient
import com.aas.app.vehicle.BydVehicleAdapter
import com.aas.app.voice.VoiceController

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
        voice = VoiceController(
            context = app,
            prefs = prefs,
            dispatcher = dispatcher,
            onStateChanged = {},
            onFinished = {},
        )
        initialized = true
    }

    fun requireInitialized(context: Context) {
        if (!initialized) initialize(context)
    }
}
