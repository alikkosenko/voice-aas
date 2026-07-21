package com.aas.app.automation

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AutomationTriggerType(val code: String) {
    STARTUP("startup"),
    SCHEDULE("schedule"),
    INTERNET_AVAILABLE("internet"),
    BUTTON("button"),
    POWER_CONNECTED("power_connected"),
    POWER_DISCONNECTED("power_disconnected"),
    SCREEN_ON("screen_on"),
    SCREEN_OFF("screen_off"),
    SOC_BELOW("soc_below"),
    SOC_ABOVE("soc_above");

    companion object {
        fun fromCode(code: String): AutomationTriggerType =
            entries.firstOrNull { it.code == code } ?: STARTUP
    }
}

enum class AutomationActionType(val code: String) {
    COMMAND("command"),
    DELAY("delay"),
    SPEAK("speak"),
    NOTIFICATION("notification"),
    LAUNCH_APP("launch_app"),
    OPEN_URL("open_url");

    companion object {
        fun fromCode(code: String): AutomationActionType =
            entries.firstOrNull { it.code == code } ?: COMMAND
    }
}

data class AutomationTrigger(
    val type: AutomationTriggerType,
    val hour: Int = 8,
    val minute: Int = 0,
    /** ISO weekday bits: bit 0 = Monday, bit 6 = Sunday; 0 means every day. */
    val daysMask: Int = 0,
    val keyCode: Int = 0,
    val threshold: Int = 20,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.code)
        put("hour", hour.coerceIn(0, 23))
        put("minute", minute.coerceIn(0, 59))
        put("daysMask", daysMask and 0x7f)
        put("keyCode", keyCode)
        put("threshold", threshold.coerceIn(0, 100))
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationTrigger = AutomationTrigger(
            type = AutomationTriggerType.fromCode(json.optString("type", "startup")),
            hour = json.optInt("hour", 8).coerceIn(0, 23),
            minute = json.optInt("minute", 0).coerceIn(0, 59),
            daysMask = json.optInt("daysMask", 0) and 0x7f,
            keyCode = json.optInt("keyCode", 0),
            threshold = json.optInt("threshold", 20).coerceIn(0, 100),
        )
    }
}

data class AutomationAction(
    val type: AutomationActionType,
    val value: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.code)
        put("value", value)
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationAction = AutomationAction(
            type = AutomationActionType.fromCode(json.optString("type", "command")),
            value = json.optString("value", ""),
        )
    }
}

data class AutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val trigger: AutomationTrigger,
    val actions: List<AutomationAction>,
    val cooldownSeconds: Int = 60,
    val stopOnError: Boolean = true,
    val requireStationary: Boolean = false,
    val runOncePerBoot: Boolean = false,
    val speakResult: Boolean = false,
    val lastRunAt: Long = 0L,
    val runCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("trigger", trigger.toJson())
        put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
        put("cooldownSeconds", cooldownSeconds.coerceIn(0, 86_400))
        put("stopOnError", stopOnError)
        put("requireStationary", requireStationary)
        put("runOncePerBoot", runOncePerBoot)
        put("speakResult", speakResult)
        put("lastRunAt", lastRunAt)
        put("runCount", runCount.coerceAtLeast(0))
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationRule {
            val actionArray = json.optJSONArray("actions") ?: JSONArray()
            val actions = buildList {
                for (i in 0 until actionArray.length()) {
                    actionArray.optJSONObject(i)?.let { add(AutomationAction.fromJson(it)) }
                }
            }
            return AutomationRule(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = json.optString("name", "Автоматизация"),
                enabled = json.optBoolean("enabled", true),
                trigger = AutomationTrigger.fromJson(json.optJSONObject("trigger") ?: JSONObject()),
                actions = actions,
                cooldownSeconds = json.optInt("cooldownSeconds", 60).coerceIn(0, 86_400),
                stopOnError = json.optBoolean("stopOnError", true),
                requireStationary = json.optBoolean("requireStationary", false),
                runOncePerBoot = json.optBoolean("runOncePerBoot", false),
                speakResult = json.optBoolean("speakResult", false),
                lastRunAt = json.optLong("lastRunAt", 0L),
                runCount = json.optInt("runCount", 0).coerceAtLeast(0),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

data class AutomationLog(
    val timestamp: Long,
    val ruleId: String,
    val ruleName: String,
    val source: String,
    val success: Boolean,
    val message: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("ruleId", ruleId)
        put("ruleName", ruleName)
        put("source", source)
        put("success", success)
        put("message", message.take(4000))
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationLog = AutomationLog(
            timestamp = json.optLong("timestamp", 0L),
            ruleId = json.optString("ruleId", ""),
            ruleName = json.optString("ruleName", ""),
            source = json.optString("source", ""),
            success = json.optBoolean("success", false),
            message = json.optString("message", ""),
        )
    }
}
