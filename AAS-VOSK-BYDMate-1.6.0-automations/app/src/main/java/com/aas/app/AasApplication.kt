package com.aas.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.aas.app.runtime.AasRuntime
import org.lsposed.hiddenapibypass.HiddenApiBypass

class AasApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;")
            }.onFailure { Log.w(TAG, "ServiceManager hidden-api exemption failed", it) }
        }
        AasRuntime.initialize(this)
    }

    companion object { private const val TAG = "AasApplication" }
}
