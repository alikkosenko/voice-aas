@file:JvmName("HelperDaemon")

package com.aas.app.helper

import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.system.exitProcess

/**
 * Small shell-uid daemon based on BYDMate's HelperDaemon lifecycle and binder wire pattern.
 * It exposes only the operations needed by the non-AI AAS assistant:
 * autoservice read/write, VIN discovery and accessibility re-bind.
 */
fun main(args: Array<String>) {
    val expectedUid = args.getOrNull(0)?.toIntOrNull() ?: exitProcess(2)

    val lockPair = acquireSingleOwnerLock(HelperBinderProtocol.LOCK_PATH) ?: run {
        println("ALREADY_RUNNING")
        exitProcess(0)
    }
    @Suppress("UNUSED_VARIABLE") val lockChannel = lockPair.first
    @Suppress("UNUSED_VARIABLE") val lockHandle = lockPair.second

    @Suppress("DEPRECATION")
    Looper.prepareMainLooper()

    val smClass = Class.forName("android.os.ServiceManager")
    val autoservice = smClass.getMethod("getService", String::class.java)
        .invoke(null, "autoservice") as? IBinder
        ?: run {
            System.err.println("ERR: autoservice not found")
            exitProcess(3)
        }
    val autoserviceDescriptor = autoservice.interfaceDescriptor.orEmpty()
    if (autoserviceDescriptor.isBlank()) {
        System.err.println("ERR: autoservice descriptor empty")
        exitProcess(3)
    }

    val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != expectedUid) return false
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)

            return when (code) {
                HelperBinderProtocol.TX_PING -> runCatching {
                    reply?.writeInt(0); reply?.writeInt(1); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_READ -> runCatching {
                    val tx = data.readInt()
                    val device = data.readInt()
                    val fid = data.readInt()
                    require(tx == 5 || tx == 7) { "unsupported read tx=$tx" }
                    val (status, value) = autoserviceTransact(
                        autoservice, autoserviceDescriptor, tx, device, fid, 0, false
                    )
                    reply?.writeInt(status); reply?.writeInt(value); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_WRITE -> runCatching {
                    val device = data.readInt()
                    val fid = data.readInt()
                    val value = data.readInt()
                    val (status, result) = autoserviceTransact(
                        autoservice, autoserviceDescriptor, 6, device, fid, value, true
                    )
                    reply?.writeInt(status); reply?.writeInt(result); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_READ_VIN -> runCatching {
                    reply?.writeInt(0)
                    reply?.writeString(findVin().orEmpty())
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeString(""); true }

                HelperBinderProtocol.TX_ENABLE_ACCESSIBILITY -> runCatching {
                    val ok = enableAccessibilityService()
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_VOLUME -> runCatching {
                    val operation = data.readInt()
                    val value = data.readInt()
                    val ok = controlGlobalVolume(operation, value)
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_WIFI -> runCatching {
                    val enabled = data.readInt() == 1
                    val ok = controlWifi(enabled)
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_APP_HIDDEN -> runCatching {
                    val pkg = data.readString().orEmpty()
                    val hidden = data.readInt()
                    val ok = if (pkg == "com.byd.autovoice" && hidden in 0..1) {
                        val cmd = if (hidden == 1) "pm disable-user --user 0" else "pm enable"
                        val primaryOk = shExec("$cmd \"\$1\"", pkg).code == 0
                        // Disable only the native assistant UI package. The vendor speech
                        // engine is also used by com.byd.autovoice.tts; disabling it leaves
                        // Android TextToSpeech initialized but completely silent.
                        val engineOk = enablePackageIfPresent("com.byd.autovoice.engine")
                        val ttsOk = enablePackageIfPresent("com.byd.autovoice.tts")
                        primaryOk && engineOk && ttsOk
                    } else false
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }


                HelperBinderProtocol.TX_ENSURE_TTS -> runCatching {
                    val engineOk = enablePackageIfPresent("com.byd.autovoice.engine")
                    val ttsOk = enablePackageIfPresent("com.byd.autovoice.tts")
                    val ok = engineOk && ttsOk
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0); true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
    binder.attachInterface(null, HelperBinderProtocol.DESCRIPTOR)

    try {
        smClass.getMethod("addService", String::class.java, IBinder::class.java)
            .invoke(null, HelperBinderProtocol.SERVICE_NAME, binder)
    } catch (e: Exception) {
        System.err.println("ERR: addService ${e.message}")
        exitProcess(4)
    }

    println("READY pid=${android.os.Process.myPid()}")
    System.out.flush()
    Looper.loop()
}

internal fun acquireSingleOwnerLock(path: String): Pair<FileChannel, FileLock>? = try {
    val channel = RandomAccessFile(path, "rw").channel
    val lock = channel.tryLock()
    if (lock == null) {
        runCatching { channel.close() }
        null
    } else channel to lock
} catch (_: OverlappingFileLockException) {
    null
} catch (_: Exception) {
    null
}

private fun autoserviceTransact(
    service: IBinder,
    descriptor: String,
    tx: Int,
    device: Int,
    fid: Int,
    value: Int,
    writeValue: Boolean
): Pair<Int, Int> {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
        data.writeInterfaceToken(descriptor)
        data.writeInt(device)
        data.writeInt(fid)
        if (writeValue) data.writeInt(value)
        val transported = service.transact(tx, data, reply, 0)
        if (!transported) return -999 to 0
        val available = reply.dataAvail()
        val status = if (available >= 4) reply.readInt() else -999
        val result = if (available >= 8) reply.readInt() else 0
        status to result
    } finally {
        data.recycle(); reply.recycle()
    }
}

/**
 * BYDMate-style forced re-bind: remove our component, wait 200 ms, add it back,
 * then enable accessibility globally. Existing third-party services are preserved.
 */
private fun enableAccessibilityService(): Boolean {
    val component = HelperBinderProtocol.ACCESSIBILITY_SERVICE_COMPONENT
    val target = canonicalComponent(component)
    val current = readSecure("enabled_accessibility_services") ?: return false
    val others = current.split(':')
        .filter { it.isNotEmpty() && canonicalComponent(it) != target }

    if (shExec(
            "settings put secure enabled_accessibility_services \"\$1\"",
            others.joinToString(":")
        ).code != 0
    ) return false

    Thread.sleep(200L)

    if (shExec(
            "settings put secure enabled_accessibility_services \"\$1\"",
            (others + component).joinToString(":")
        ).code != 0
    ) return false

    if (shExec("settings put secure accessibility_enabled 1").code != 0) return false

    val after = (readSecure("enabled_accessibility_services") ?: return false)
        .split(':').filter { it.isNotEmpty() }
    return after.any { canonicalComponent(it) == target } &&
        readSecure("accessibility_enabled") == "1"
}

internal fun canonicalComponent(flattened: String): String {
    val separator = flattened.indexOf('/')
    if (separator < 0) return flattened
    val packageName = flattened.substring(0, separator)
    val className = flattened.substring(separator + 1)
    val fullClass = if (className.startsWith(".")) packageName + className else className
    return "$packageName/$fullClass"
}

private fun readSecure(key: String): String? {
    val result = shExec("settings get secure \"\$1\"", key)
    if (result.code != 0) return null
    return if (result.stdout == "null") "" else result.stdout
}

private fun enablePackageIfPresent(packageName: String): Boolean {
    val path = shExec("pm path \"\$1\"", packageName)
    if (path.code != 0 || path.stdout.isBlank()) return true
    return shExec("pm enable \"\$1\"", packageName).code == 0
}

private fun controlWifi(enabled: Boolean): Boolean {
    val legacyState = if (enabled) "enable" else "disable"
    val modernState = if (enabled) "enabled" else "disabled"
    val numericState = if (enabled) "1" else "0"

    // BYD generations expose different Android shell interfaces.
    val attempts = listOf(
        shExec("svc wifi $legacyState"),
        shExec("cmd wifi set-wifi-enabled $modernState"),
        shExec("settings put global wifi_on $numericState")
    )
    return attempts.any { it.code == 0 }
}

private data class CmdResult(val code: Int, val stdout: String)

private fun shExec(script: String, vararg args: String): CmdResult {
    val command = arrayListOf("sh", "-c", script, "sh")
    command.addAll(args)
    val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    return CmdResult(process.waitFor(), output)
}

private fun findVin(): String? {
    val regex = Regex("[A-HJ-NPR-Z0-9]{17}")
    val knownProperties = listOf(
        "ro.boot.vin", "ro.vendor.vin", "persist.vendor.vin", "persist.sys.vin",
        "persist.byd.vin", "sys.byd.vin", "vehicle.vin", "vendor.vehicle.vin"
    )

    fun normalize(value: String?): String? {
        val cleaned = value.orEmpty().uppercase().replace(Regex("[^A-Z0-9]"), "")
        return cleaned.takeIf { it.matches(regex) }
    }

    knownProperties.forEach { property ->
        val output = shExec("getprop \"\$1\"", property)
        if (output.code == 0) normalize(output.stdout)?.let { return it }
    }

    val allProperties = shExec("getprop")
    if (allProperties.code == 0) {
        allProperties.stdout.lineSequence().forEach { line ->
            if (line.contains("vin", ignoreCase = true)) {
                regex.find(line.uppercase())?.value?.let { return it }
            }
        }
    }

    listOf(
        "/persist/vin", "/mnt/vendor/persist/vin", "/vendor/persist/vin",
        "/data/vendor/vehicle/vin", "/data/vendor/byd/vin"
    ).forEach { path ->
        runCatching { File(path).takeIf { it.isFile }?.readText()?.let(::normalize) }
            .getOrNull()?.let { return it }
    }
    return null
}


/**
 * BYD/Android head units often ignore AudioManager calls from ordinary apps.
 * Shell-injected hardware key events are handled by the active automotive audio
 * policy, so they affect the same volume shown by the vehicle UI. Absolute set
 * first tries the available Android shell interfaces and falls back to key steps.
 */
private fun controlGlobalVolume(operation: Int, value: Int): Boolean {
    fun key(code: Int): Boolean = shExec("input keyevent \"\$1\"", code.toString()).code == 0

    fun readMediaVolume(): Int? {
        val commands = listOf(
            "cmd media_session volume --stream 3 --get",
            "media volume --stream 3 --get"
        )
        for (command in commands) {
            val result = shExec(command)
            if (result.code != 0) continue
            Regex("(?i)volume(?:\\s+is)?\\D*(\\d+)")
                .find(result.stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    fun keySteps(code: Int, count: Int): Boolean {
        var ok = true
        repeat(count.coerceAtLeast(0)) {
            if (!key(code)) ok = false
            Thread.sleep(12L)
        }
        return ok
    }

    return when (operation) {
        HelperBinderProtocol.VOLUME_UP -> key(24)
        HelperBinderProtocol.VOLUME_DOWN -> key(25)
        HelperBinderProtocol.VOLUME_MUTE -> key(164)
        HelperBinderProtocol.VOLUME_UNMUTE -> key(24)
        HelperBinderProtocol.VOLUME_SET -> {
            val target = value.coerceIn(0, 100)
            val commands = listOf(
                "cmd media_session volume --stream 3 --set \"\$1\"",
                "media volume --stream 3 --set \"\$1\"",
                "cmd audio set-stream-volume 3 \"\$1\""
            )
            commands.any { shExec(it, target.toString()).code == 0 }
            Thread.sleep(80L)
            if (readMediaVolume() == target) {
                true
            } else {
                // The shell API often returns exit code 0 on DiLink without moving
                // the automotive volume group. Hardware keys always enter the active
                // vehicle audio policy, so normalize to zero and raise to the target.
                val lowered = keySteps(25, 60)
                val raised = keySteps(24, target)
                val readback = readMediaVolume()
                lowered && raised && (readback == null || readback == target)
            }
        }
        else -> false
    }
}
