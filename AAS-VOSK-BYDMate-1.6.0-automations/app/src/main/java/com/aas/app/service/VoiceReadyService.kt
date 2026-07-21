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
import com.aas.app.auth.AuthState
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
        if (!AuthState.isAuthorized(this)) {
            stopSelf()
            return
        }
        // startForegroundService() has a strict startup deadline on Android 8+.
        // Publish the notification before constructing/preloading the runtime.
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.service_starting)))
        AasRuntime.requireInitialized(this)
        if (AasRuntime.prefs.enabled) AasRuntime.voice.preload()
        AasRuntime.automation.startMonitoring()
        AasRuntime.automation.fireStartup()
        worker.scheduleWithFixedDelay(
            { runCatching { AasRuntime.automation.tick() }.onFailure { Log.e(TAG, "automation tick failed", it) } },
            AUTOMATION_INITIAL_DELAY_SECONDS,
            AUTOMATION_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )

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
        if (!AuthState.isAuthorized(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (AasRuntime.prefs.enabled) AasRuntime.voice.preload()
        if (intent?.action == ACTION_REPAIR_NOW) {
            worker.execute { repairRuntime(force = true) }
        }
        return START_STICKY
    }

    private fun repairRuntime(force: Boolean) {
        if (!repairRunning.compareAndSet(false, true)) return
        try {
            if (!AuthState.isAuthorized(this)) {
                stopSelf()
                return
            }
            if (AasRuntime.prefs.enabled) AasRuntime.voice.preload()
            val voiceEnabled = AasRuntime.prefs.enabled
            val automationsEnabled = AasRuntime.automationRepository.masterEnabled
            if (!voiceEnabled && !automationsEnabled) {
                updateNotification(getString(R.string.service_voice_disabled))
                return
            }

            val helperHealthy = runCatching { AasRuntime.bootstrap.isHealthy() }.getOrDefault(false)
            val accessibilityHealthy = AasAccessibilityService.isConnected
            if (!force && helperHealthy && accessibilityHealthy) {
                updateNotification(getString(R.string.service_ready))
                return
            }

            val result = AasRuntime.bootstrap.ensureRunning(enableAccessibility = true)
            val localizedResult = localizeRuntime(result.message)
            AasRuntime.prefs.lastResult = localizedResult

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
        runCatching { AasRuntime.automation.stopMonitoring() }
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
        private const val AUTOMATION_INITIAL_DELAY_SECONDS = 5L
        private const val AUTOMATION_INTERVAL_SECONDS = 15L
        private const val TAG = "AasVoiceReadyService"
    }
}
