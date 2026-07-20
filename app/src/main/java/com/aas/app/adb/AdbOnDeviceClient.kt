package com.aas.app.adb

import android.content.Context
import android.util.Log
import com.aas.app.helper.HelperBinderProtocol
import java.io.IOException

/**
 * On-device ADB transport based on BYDMate's proven DiLink path.
 *
 * It talks directly to 127.0.0.1:5555, persists one RSA key, launches an
 * app_process helper under shell uid and keeps that ADB socket for reuse.
 */
class AdbOnDeviceClient(
    context: Context,
    keyStore: AdbKeyStore = AdbKeyStore(context)
) {
    private val appContext = context.applicationContext
    private val protocol: AdbProtocol = AdbProtocolClient(keyStore.loadOrGenerate())

    @Synchronized
    fun connect(): Result<Unit> = try {
        if (protocol.connect()) Result.success(Unit)
        else Result.failure(IOException("ADB connect refused or authorization was not accepted"))
    } catch (e: Exception) {
        Log.w(TAG, "connect failed", e)
        Result.failure(e)
    }

    @Synchronized
    fun isConnected(): Boolean = protocol.isConnected()

    @Synchronized
    fun execRaw(command: String): String? {
        return try {
            if (!protocol.isConnected() && !protocol.connect()) {
                null
            } else {
                protocol.exec(command)
            }
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            null
        }
    }

    fun spawnHelper(): Boolean {
        val spawnCmd =
            "CLASSPATH=${appContext.packageCodePath} setsid app_process /system/bin " +
                "--nice-name=${HelperBinderProtocol.PROCESS_NAME} " +
                "com.aas.app.helper.HelperDaemon ${android.os.Process.myUid()} " +
                "</dev/null >${HelperBinderProtocol.LOG_PATH} 2>&1 & " +
                "for i in 1 2 3; do service list 2>/dev/null | " +
                "grep -q ${HelperBinderProtocol.SERVICE_NAME} && break; sleep 1; done"
        return execRaw(spawnCmd) != null
    }

    fun killHelper(): Boolean {
        val command =
            "for p in \$(ps -A -o PID,NAME | awk '\$2==\"${HelperBinderProtocol.PROCESS_NAME}\"{print \$1}'); " +
                "do kill -9 \$p; done"
        return execRaw(command) != null
    }

    fun helperHeartbeat(): Boolean {
        val out = execRaw(
            "ps -A -o NAME | awk '\$1==\"${HelperBinderProtocol.PROCESS_NAME}\"{print \$1; exit}'"
        ) ?: return false
        return out.lineSequence().any { it.trim() == HelperBinderProtocol.PROCESS_NAME }
    }

    fun readHelperLog(): String? = execRaw("cat ${HelperBinderProtocol.LOG_PATH} 2>/dev/null")

    @Synchronized
    fun shutdown() = protocol.disconnect()

    companion object {
        private const val TAG = "AasAdbOnDevice"
    }
}
