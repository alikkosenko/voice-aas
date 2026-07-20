package com.aas.app.helper

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/** In-app client for the shell-uid helper registered in ServiceManager. */
class HelperClient {
    @Volatile private var cached: IBinder? = null

    data class Value(val status: Int, val value: Int) {
        val readSucceeded: Boolean get() = status == 0
        val writeAccepted: Boolean get() = status >= 0
    }

    @Synchronized
    fun isAlive(): Boolean = transactPair(HelperBinderProtocol.TX_PING) != null

    @Synchronized
    fun read(tx: Int, device: Int, fid: Int): Value? =
        transactPair(HelperBinderProtocol.TX_READ) {
            it.writeInt(tx); it.writeInt(device); it.writeInt(fid)
        }?.let { Value(it.first, it.second) }

    @Synchronized
    fun write(device: Int, fid: Int, value: Int): Value? =
        transactPair(HelperBinderProtocol.TX_WRITE) {
            it.writeInt(device); it.writeInt(fid); it.writeInt(value)
        }?.let { Value(it.first, it.second) }

    @Synchronized
    fun readVin(): String? = transactParsed(HelperBinderProtocol.TX_READ_VIN, {}) { reply ->
        if (reply.dataAvail() < 4) return@transactParsed null
        if (reply.readInt() != 0) return@transactParsed null
        reply.readString()?.trim()?.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
    }

    @Synchronized
    fun enableAccessibilityService(): Boolean =
        transactPair(HelperBinderProtocol.TX_ENABLE_ACCESSIBILITY)?.first == 0


    /** Controls the global media volume through the shell-uid helper. */
    @Synchronized
    fun controlVolume(operation: Int, value: Int = 0): Boolean =
        transactPair(HelperBinderProtocol.TX_VOLUME) {
            it.writeInt(operation)
            it.writeInt(value)
        }?.first == 0

    /** Enables or disables Wi-Fi through the shell-uid helper. */
    @Synchronized
    fun setWifiEnabled(enabled: Boolean): Boolean =
        transactPair(HelperBinderProtocol.TX_WIFI) {
            it.writeInt(if (enabled) 1 else 0)
        }?.first == 0

    /** BYDMate-compatible reversible disable/enable of the native BYD assistant family. */
    @Synchronized
    fun setNativeAssistantDisabled(disabled: Boolean): Boolean =
        transactPair(HelperBinderProtocol.TX_SET_APP_HIDDEN) {
            it.writeString("com.byd.autovoice")
            it.writeInt(if (disabled) 1 else 0)
        }?.first == 0

    /** Re-enables the BYD TTS engine without turning the native assistant back on. */
    @Synchronized
    fun ensureTtsEngineEnabled(): Boolean =
        transactPair(HelperBinderProtocol.TX_ENSURE_TTS)?.first == 0

    private fun transactPair(code: Int, write: (Parcel) -> Unit = {}): Pair<Int, Int>? =
        transactParsed(code, write) { reply ->
            if (reply.dataAvail() < 4) return@transactParsed null
            val status = reply.readInt()
            val value = if (reply.dataAvail() >= 4) reply.readInt() else 0
            status to value
        }

    private fun <T> transactParsed(code: Int, write: (Parcel) -> Unit, parse: (Parcel) -> T?): T? {
        repeat(2) { attempt ->
            val binder = ensureBinder() ?: return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(HelperBinderProtocol.DESCRIPTOR)
                write(data)
                if (!binder.transact(code, data, reply, 0)) return null
                return parse(reply)
            } catch (e: DeadObjectException) {
                cached = null
                if (attempt == 1) Log.w(TAG, "binder dead after retry", e)
            } catch (e: Exception) {
                Log.w(TAG, "transact $code failed", e)
                return null
            } finally {
                data.recycle(); reply.recycle()
            }
        }
        return null
    }

    private fun ensureBinder(): IBinder? {
        cached?.takeIf { it.isBinderAlive }?.let { return it }
        return resolveBinder()?.also { cached = it }
    }

    private fun resolveBinder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java)
            .invoke(null, HelperBinderProtocol.SERVICE_NAME) as? IBinder
    } catch (e: Exception) {
        Log.w(TAG, "getService failed: ${e.message}")
        null
    }

    companion object { private const val TAG = "AasHelperClient" }
}
