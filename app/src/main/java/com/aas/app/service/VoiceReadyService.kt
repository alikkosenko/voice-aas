package com.aas.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.aas.app.MainActivity
import com.aas.app.R
import com.aas.app.UkrainianTranslator
import com.aas.app.accessibility.AasAccessibilityService
import com.aas.app.runtime.AasRuntime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the process, Vosk model, on-device ADB helper and steering-wheel
 * AccessibilityService alive.
 *
 * BYDMate does not rely on the missing DiLink Accessibility settings screen:
 * its long-lived tracking service periodically verifies the true service
 * liveness flag and re-asserts the secure setting through the shell helper.
 * AAS uses the same lifecycle pattern here.
 */
class VoiceReadyService : Service() {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val repairRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        AasRuntime.requireInitialized(this)
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.service_starting)))
        AasRuntime.voice.preload()

        // Immediate attempt makes the native ADB RSA dialog appear without the
        // user having to race into a settings screen first.
        worker.execute { repairRuntime(force = true) }
        worker.scheduleWithFixedDelay(
            { repairRuntime(force = false) },
            WATCHDOG_INITIAL_DELAY_SECONDS,
            WATCHDOG_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AasRuntime.voice.preload()
        if (intent?.action == ACTION_REPAIR_NOW) {
            worker.execute { repairRuntime(force = true) }
        }
        return START_STICKY
    }

    private fun repairRuntime(force: Boolean) {
        if (!repairRunning.compareAndSet(false, true)) return
        try {
            AasRuntime.voice.preload()
            if (!AasRuntime.prefs.enabled) {
                updateNotification(getString(R.string.service_voice_disabled))
                return
            }

            val helperHealthy = runCatching { AasRuntime.bootstrap.isHealthy() }.getOrDefault(false)
            val accessibilityHealthy = AasAccessibilityService.isConnected
            if (!force && helperHealthy && accessibilityHealthy) {
                if (AasRuntime.prefs.voiceResponsesEnabled) {
                    runCatching { AasRuntime.helper.ensureTtsEngineEnabled() }
                    AasRuntime.voice.reinitializeTts()
                }
                updateNotification(getString(R.string.service_ready))
                return
            }

            val result = AasRuntime.bootstrap.ensureRunning(enableAccessibility = true)
            val localizedResult = localizeRuntime(result.message)
            AasRuntime.prefs.lastResult = localizedResult

            // Keep the vendor speech engine and TTS service enabled even when the
            // native assistant UI is disabled, then recreate the process-wide client.
            if (result.connected && AasRuntime.prefs.voiceResponsesEnabled) {
                runCatching { AasRuntime.helper.ensureTtsEngineEnabled() }
                AasRuntime.voice.reinitializeTts()
            }

            // The secure setting write is synchronous, but framework binding is
            // asynchronous. Wait briefly so the watchdog reports real liveness.
            if (result.accessibilityEnabled && !AasAccessibilityService.isConnected) {
                var attempts = 0
                while (!AasAccessibilityService.isConnected && attempts < 30) {
                    Thread.sleep(200L)
                    attempts++
                }
            }

            val status = when {
                result.connected && AasAccessibilityService.isConnected -> getString(R.string.service_ready)
                result.connected -> localizedResult
                else -> localizedResult
            }
            updateNotification(status)
            Log.i(TAG, "runtime repair: $status")
        } catch (t: Throwable) {
            Log.e(TAG, "runtime repair failed", t)
            val error = if (AasRuntime.prefs.languageTag.startsWith("uk", ignoreCase = true)) {
                "Помилка запуску ADB/helper: ${t.message ?: t.javaClass.simpleName}"
            } else {
                "Ошибка запуска ADB/helper: ${t.message ?: t.javaClass.simpleName}"
            }
            AasRuntime.prefs.lastResult = error
            updateNotification(error)
        } finally {
            repairRunning.set(false)
        }
    }


    private fun localizeRuntime(text: String): String =
        if (AasRuntime.prefs.languageTag.startsWith("uk", ignoreCase = true)) {
            UkrainianTranslator.translate(text)
        } else text

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_aas)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text.take(120))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    companion object {
        const val ACTION_REPAIR_NOW = "com.aas.app.action.REPAIR_RUNTIME"
        private const val CHANNEL_ID = "aas_voice_ready"
        private const val NOTIFICATION_ID = 2101
        private const val WATCHDOG_INITIAL_DELAY_SECONDS = 20L
        private const val WATCHDOG_INTERVAL_SECONDS = 60L
        private const val TAG = "AasVoiceReadyService"
    }
}
