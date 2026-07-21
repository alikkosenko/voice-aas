package com.aas.app.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aas.app.AppPrefs
import com.aas.app.R
import com.aas.app.commands.ExecutionResult
import com.aas.app.commands.TextCommandExecutor
import com.aas.app.vehicle.BydVehicleAdapter
import com.aas.app.voice.SpeechOutput
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Event-only automation engine. It never listens to the microphone and never
 * intercepts recognized speech. Command actions use the deterministic local
 * parser; OpenAI fallback is intentionally disabled for background rules.
 */
class AutomationEngine(
    context: Context,
    private val prefs: AppPrefs,
    val repository: AutomationRepository,
    private val textExecutor: TextCommandExecutor,
    private val speechOutput: SpeechOutput,
    private val vehicle: BydVehicleAdapter,
) {
    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val runningRules = ConcurrentHashMap.newKeySet<String>()
    private val executedThisBoot = ConcurrentHashMap.newKeySet<String>()
    private val startupConsumed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val notificationIds = AtomicInteger(3100)
    private val claimedScheduleMinutes = ConcurrentHashMap<String, Long>()

    @Volatile private var lastInternetAvailable: Boolean? = null
    @Volatile private var lastSoc: Int? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val trigger = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> AutomationTriggerType.POWER_CONNECTED
                Intent.ACTION_POWER_DISCONNECTED -> AutomationTriggerType.POWER_DISCONNECTED
                Intent.ACTION_SCREEN_ON -> AutomationTriggerType.SCREEN_ON
                Intent.ACTION_SCREEN_OFF -> AutomationTriggerType.SCREEN_OFF
                else -> null
            } ?: return
            fireTrigger(trigger, "system:${trigger.code}")
        }
    }

    fun startMonitoring() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                systemReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            receiverRegistered.set(false)
            Log.w(TAG, "Unable to register automation event receiver", it)
        }
    }

    fun stopMonitoring() {
        if (!receiverRegistered.compareAndSet(true, false)) return
        runCatching { appContext.unregisterReceiver(systemReceiver) }
            .onFailure { Log.w(TAG, "Unable to unregister automation receiver", it) }
    }

    fun hasButtonRule(keyCode: Int): Boolean = repository.masterEnabled && repository.listRules().any {
        it.enabled && it.trigger.type == AutomationTriggerType.BUTTON && it.trigger.keyCode == keyCode
    }

    /** Returns true when at least one enabled button rule consumed this key. */
    fun onButtonPressed(keyCode: Int): Boolean {
        if (!repository.masterEnabled) return false
        val rules = repository.listRules().filter {
            it.enabled && it.trigger.type == AutomationTriggerType.BUTTON && it.trigger.keyCode == keyCode
        }
        if (rules.isEmpty()) return false
        rules.forEach { executeAsync(it, "button:$keyCode") }
        return true
    }

    fun fireStartup() {
        if (!repository.masterEnabled || !startupConsumed.compareAndSet(false, true)) return
        fireTrigger(AutomationTriggerType.STARTUP, "startup")
    }

    /** Called periodically by the foreground service. */
    fun tick() {
        if (!repository.masterEnabled) return
        evaluateSchedules()
        evaluateInternetEdge()
        evaluateSocEdges()
    }

    fun runNow(ruleId: String, callback: (ExecutionResult) -> Unit = {}) {
        val rule = repository.getRule(ruleId)
        if (rule == null) {
            callback(ExecutionResult(false, localized("Автоматизация не найдена", "Автоматизацію не знайдено")))
            return
        }
        executeAsync(rule, "manual", ignoreCooldown = true, callback = callback)
    }

    fun shutdown() {
        stopMonitoring()
        worker.shutdownNow()
    }

    private fun fireTrigger(type: AutomationTriggerType, source: String) {
        if (!repository.masterEnabled) return
        repository.listRules()
            .filter { it.enabled && it.trigger.type == type }
            .forEach { executeAsync(it, source) }
    }

    private fun evaluateSchedules() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val isoDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }
        val minuteStart = calendar.apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        repository.listRules().filter { rule ->
            if (!rule.enabled || rule.trigger.type != AutomationTriggerType.SCHEDULE) return@filter false
            val trigger = rule.trigger
            val dayAllowed = trigger.daysMask == 0 || (trigger.daysMask and (1 shl (isoDay - 1))) != 0
            val notAlreadyClaimed = claimedScheduleMinutes[rule.id] != minuteStart
            dayAllowed && trigger.hour == hour && trigger.minute == minute &&
                rule.lastRunAt < minuteStart && notAlreadyClaimed
        }.forEach { rule ->
            claimedScheduleMinutes[rule.id] = minuteStart
            executeAsync(rule, "schedule")
        }
        claimedScheduleMinutes.entries.removeIf { (_, claimedMinute) -> claimedMinute < minuteStart - 120_000L }
    }

    private fun evaluateInternetEdge() {
        val available = isInternetAvailable()
        val previous = lastInternetAvailable
        lastInternetAvailable = available
        if (previous == false && available) {
            fireTrigger(AutomationTriggerType.INTERNET_AVAILABLE, "internet")
        }
    }

    private fun evaluateSocEdges() {
        val rules = repository.listRules().filter {
            it.enabled && it.trigger.type in setOf(AutomationTriggerType.SOC_BELOW, AutomationTriggerType.SOC_ABOVE)
        }
        if (rules.isEmpty()) return
        val current = vehicle.readAutomationSnapshot().stateOfChargePercent ?: return
        val previous = lastSoc
        lastSoc = current
        if (previous == null) return
        rules.forEach { rule ->
            val threshold = rule.trigger.threshold.coerceIn(0, 100)
            val crossed = when (rule.trigger.type) {
                AutomationTriggerType.SOC_BELOW -> previous >= threshold && current < threshold
                AutomationTriggerType.SOC_ABOVE -> previous <= threshold && current > threshold
                else -> false
            }
            if (crossed) executeAsync(rule, "soc:$previous->$current")
        }
    }

    private fun isInternetAvailable(): Boolean = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun executeAsync(
        rule: AutomationRule,
        source: String,
        ignoreCooldown: Boolean = false,
        callback: (ExecutionResult) -> Unit = {},
    ) {
        worker.execute {
            val result = executeBlocking(rule, source, ignoreCooldown)
            callback(result)
        }
    }

    private fun executeBlocking(
        initialRule: AutomationRule,
        source: String,
        ignoreCooldown: Boolean = false,
    ): ExecutionResult {
        val rule = repository.getRule(initialRule.id) ?: initialRule
        if (source != "manual" && (!repository.masterEnabled || !rule.enabled)) {
            return ExecutionResult(false, localized("Автоматизация выключена", "Автоматизацію вимкнено"))
        }
        if (rule.runOncePerBoot && source != "manual" && rule.id in executedThisBoot) {
            return ExecutionResult(false, localized("Автоматизация уже выполнялась после запуска", "Автоматизація вже виконувалась після запуску"))
        }
        if (!runningRules.add(rule.id)) {
            return ExecutionResult(false, localized("Автоматизация уже выполняется", "Автоматизація вже виконується"))
        }
        try {
            val now = System.currentTimeMillis()
            val cooldownMs = rule.cooldownSeconds.coerceAtLeast(0) * 1000L
            if (!ignoreCooldown && rule.lastRunAt > 0 && now - rule.lastRunAt < cooldownMs) {
                return ExecutionResult(
                    false,
                    localized("Автоматизация пропущена: действует пауза", "Автоматизацію пропущено: діє пауза"),
                    "cooldown active; remainingMs=${cooldownMs - (now - rule.lastRunAt)}",
                )
            }
            if (rule.requireStationary) {
                val (stationary, details) = vehicle.checkStationaryForAutomation()
                if (stationary != true) {
                    return finish(
                        rule,
                        source,
                        false,
                        localized("Автоматизация отменена: автомобиль не остановлен", "Автоматизацію скасовано: автомобіль не зупинено"),
                        "stationary=$stationary; $details",
                    )
                }
            }
            if (rule.actions.isEmpty()) {
                return finish(rule, source, false, localized("В автоматизации нет действий", "В автоматизації немає дій"))
            }

            val technical = mutableListOf<String>()
            var success = true
            for ((index, action) in rule.actions.withIndex()) {
                val result = executeAction(action)
                technical += "#${index + 1} ${action.type.code}: success=${result.success}; ${result.technicalMessage}"
                if (!result.success) {
                    success = false
                    if (rule.stopOnError) break
                }
            }

            val summary = if (success) {
                localized("Автоматизация «${rule.name}» выполнена", "Автоматизацію «${rule.name}» виконано")
            } else {
                localized("Автоматизация «${rule.name}» выполнена с ошибкой", "Автоматизацію «${rule.name}» виконано з помилкою")
            }
            val result = finish(rule, source, success, summary, technical.joinToString("\n"))
            if (success && rule.runOncePerBoot) executedThisBoot.add(rule.id)
            if (rule.speakResult && prefs.voiceResponsesEnabled) {
                speechOutput.speakResult(result.spokenMessage, result.success)
            }
            return result
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return finish(rule, source, false, localized("Автоматизация прервана", "Автоматизацію перервано"), error.message ?: "interrupted")
        } catch (error: Throwable) {
            Log.e(TAG, "automation failed: ${rule.name}", error)
            return finish(rule, source, false, localized("Ошибка автоматизации", "Помилка автоматизації"), error.stackTraceToString())
        } finally {
            runningRules.remove(rule.id)
        }
    }

    private fun executeAction(action: AutomationAction): ExecutionResult {
        return when (action.type) {
            AutomationActionType.COMMAND -> textExecutor.execute(action.value.trim(), allowAiFallback = false)
            AutomationActionType.DELAY -> {
                val millis = action.value.toLongOrNull()?.coerceIn(0L, 60_000L)
                    ?: return ExecutionResult(false, localized("Некорректная задержка", "Некоректна затримка"))
                Thread.sleep(millis)
                ExecutionResult(true, "", "delayMs=$millis")
            }
            AutomationActionType.SPEAK -> {
                val text = action.value.trim()
                if (text.isBlank()) ExecutionResult(false, localized("Пустой текст озвучивания", "Порожній текст озвучування"))
                else {
                    speechOutput.speak(text)
                    ExecutionResult(true, "", "spoken=${text.take(120)}")
                }
            }
            AutomationActionType.NOTIFICATION -> postNotification(action.value.trim())
            AutomationActionType.LAUNCH_APP -> launchApp(action.value.trim())
            AutomationActionType.OPEN_URL -> openUrl(action.value.trim())
        }
    }

    private fun postNotification(text: String): ExecutionResult {
        if (text.isBlank()) return ExecutionResult(false, localized("Пустой текст уведомления", "Порожній текст сповіщення"))
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, localized("Автоматизации AAS", "Автоматизації AAS"), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, AutomationsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(appContext, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(appContext)
        manager.notify(
            notificationIds.incrementAndGet(),
            builder.setSmallIcon(R.drawable.ic_aas)
                .setContentTitle(localized("AAS — автоматизация", "AAS — автоматизація"))
                .setContentText(text.take(180))
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return ExecutionResult(true, "", "notification=${text.take(120)}")
    }

    private fun launchApp(packageName: String): ExecutionResult {
        if (packageName.isBlank()) return ExecutionResult(false, localized("Не указан пакет приложения", "Не вказано пакет застосунку"))
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ExecutionResult(false, localized("Приложение не установлено", "Застосунок не встановлено"), "package=$packageName")
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            ExecutionResult(true, localized("Приложение открыто", "Застосунок відкрито"), "package=$packageName")
        }.getOrElse { ExecutionResult(false, localized("Не удалось открыть приложение", "Не вдалося відкрити застосунок"), it.message ?: "launch failed") }
    }

    private fun openUrl(raw: String): ExecutionResult {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (raw.isBlank() || uri?.scheme !in setOf("http", "https", "geo")) {
            return ExecutionResult(false, localized("Некорректная ссылка", "Некоректне посилання"), raw)
        }
        return runCatching {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ExecutionResult(true, localized("Ссылка открыта", "Посилання відкрито"), raw)
        }.getOrElse { ExecutionResult(false, localized("Не удалось открыть ссылку", "Не вдалося відкрити посилання"), it.message ?: "url failed") }
    }

    private fun finish(
        rule: AutomationRule,
        source: String,
        success: Boolean,
        spoken: String,
        technical: String = spoken,
    ): ExecutionResult {
        val now = System.currentTimeMillis()
        repository.updateRunStats(rule.id, now)
        repository.addLog(
            AutomationLog(
                timestamp = now,
                ruleId = rule.id,
                ruleName = rule.name,
                source = source,
                success = success,
                message = technical,
            )
        )
        return ExecutionResult(success, spoken, technical)
    }

    private fun localized(russian: String, ukrainian: String): String =
        if (prefs.languageTag.startsWith("uk", ignoreCase = true)) ukrainian else russian

    companion object {
        private const val TAG = "AasAutomationEngine"
        private const val CHANNEL_ID = "aas_automations"
    }
}
