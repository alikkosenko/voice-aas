package com.aas.app.helper

import android.content.Context
import android.os.Build
import android.util.Log
import com.aas.app.adb.AdbOnDeviceClient

/** Result shown to the user after an ADB/helper bootstrap attempt. */
data class BootstrapResult(
    val connected: Boolean,
    val accessibilityEnabled: Boolean,
    val message: String,
    val adbFingerprint: String? = null,
)

/**
 * Synchronous, serialized helper lifecycle owner based on BYDMate's HelperBootstrap.
 * Call it only from a background thread.
 */
class HelperBootstrap(
    context: Context,
    private val adb: AdbOnDeviceClient,
    private val helper: HelperClient,
    private val keyFingerprint: () -> String,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun ensureRunning(enableAccessibility: Boolean = false): BootstrapResult {
        val wantedVersion = installedVersionCode()
        val spawnedFor = prefs.getLong(KEY_SPAWNED_VERSION, NO_STORED_VERSION)

        if (spawnedFor == wantedVersion && helper.isAlive()) {
            val accessibility = if (enableAccessibility) helper.enableAccessibilityService() else false
            return BootstrapResult(
                connected = true,
                accessibilityEnabled = accessibility,
                message = if (!enableAccessibility || accessibility) {
                    "ADB helper подключён${if (accessibility) ", Accessibility включён" else ""}"
                } else {
                    "Helper работает, но не удалось включить Accessibility"
                },
                adbFingerprint = keyFingerprint(),
            )
        }

        val connection = adb.connect()
        if (connection.isFailure) {
            return BootstrapResult(
                connected = false,
                accessibilityEnabled = false,
                message = "Не удалось подключиться к ADB 127.0.0.1:5555: " +
                    (connection.exceptionOrNull()?.message ?: "неизвестная ошибка"),
                adbFingerprint = keyFingerprint(),
            )
        }

        val helperProcessExists = adb.helperHeartbeat()
        if (spawnedFor != wantedVersion || helperProcessExists) {
            if (!adb.killHelper()) {
                return BootstrapResult(
                    false, false,
                    "ADB подключён, но не удалось остановить старый helper",
                    keyFingerprint()
                )
            }
            var alive = adb.helperHeartbeat()
            var attempts = 0
            while (alive && attempts < KILL_CONFIRM_ATTEMPTS) {
                Thread.sleep(KILL_CONFIRM_INTERVAL_MS)
                attempts++
                alive = adb.helperHeartbeat()
            }
            if (alive) {
                return BootstrapResult(
                    false, false,
                    "Старый helper не завершился; перезапусти мультимедиа",
                    keyFingerprint()
                )
            }
        }

        if (!adb.spawnHelper()) {
            return BootstrapResult(
                false, false,
                "ADB подключён, но команда запуска helper не выполнилась",
                keyFingerprint()
            )
        }

        var helperReady = false
        repeat(POLL_ATTEMPTS) {
            Thread.sleep(POLL_INTERVAL_MS)
            if (helper.isAlive()) {
                helperReady = true
                return@repeat
            }
        }

        if (!helperReady) {
            val log = adb.readHelperLog().orEmpty().takeLast(1200)
            return BootstrapResult(
                false, false,
                "Helper не зарегистрировался. Лог: ${log.ifBlank { "пусто" }}",
                keyFingerprint()
            )
        }

        prefs.edit().putLong(KEY_SPAWNED_VERSION, wantedVersion).apply()
        val accessibility = if (enableAccessibility) helper.enableAccessibilityService() else false
        return BootstrapResult(
            connected = true,
            accessibilityEnabled = accessibility,
            message = when {
                !enableAccessibility -> "ADB и helper подключены"
                accessibility -> "ADB, helper и Accessibility подключены"
                else -> "ADB и helper подключены, но Accessibility не включился"
            },
            adbFingerprint = keyFingerprint(),
        )
    }

    fun isHealthy(): Boolean = helper.isAlive()

    private fun installedVersionCode(): Long = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(VERSION_READ_FAILED)

    companion object {
        private const val TAG = "AasHelperBootstrap"
        private const val PREFS = "helper"
        private const val KEY_SPAWNED_VERSION = "spawned_version_code"
        private const val NO_STORED_VERSION = -1L
        private const val VERSION_READ_FAILED = Long.MIN_VALUE
        private const val POLL_ATTEMPTS = 20
        private const val POLL_INTERVAL_MS = 200L
        private const val KILL_CONFIRM_ATTEMPTS = 15
        private const val KILL_CONFIRM_INTERVAL_MS = 100L
    }
}
