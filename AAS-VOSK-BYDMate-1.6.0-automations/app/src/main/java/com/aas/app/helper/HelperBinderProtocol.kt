package com.aas.app.helper

import android.os.IBinder

/** Minimal BYDMate-style binder contract for AAS. */
object HelperBinderProtocol {
    const val SERVICE_NAME = "aas_helper"
    const val PROCESS_NAME = "aas_helper"
    const val DESCRIPTOR = "com.aas.app.helper.IHelper"
    const val LOG_PATH = "/data/local/tmp/aas_helper.log"
    const val LOCK_PATH = "/data/local/tmp/aas_helper.lock"

    const val TX_PING = IBinder.FIRST_CALL_TRANSACTION
    const val TX_READ = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TX_WRITE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TX_READ_VIN = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TX_ENABLE_ACCESSIBILITY = IBinder.FIRST_CALL_TRANSACTION + 15
    const val TX_SET_APP_HIDDEN = IBinder.FIRST_CALL_TRANSACTION + 17
    const val TX_VOLUME = IBinder.FIRST_CALL_TRANSACTION + 18
    const val TX_WIFI = IBinder.FIRST_CALL_TRANSACTION + 19
    const val TX_ENSURE_TTS = IBinder.FIRST_CALL_TRANSACTION + 20

    const val VOLUME_SET = 0
    const val VOLUME_UP = 1
    const val VOLUME_DOWN = 2
    const val VOLUME_MUTE = 3
    const val VOLUME_UNMUTE = 4

    const val ACCESSIBILITY_SERVICE_COMPONENT =
        "com.aas.app/com.aas.app.accessibility.AasAccessibilityService"
}
