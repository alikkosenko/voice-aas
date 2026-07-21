package com.aas.app.automation

import android.content.Context
import org.json.JSONArray

/** Lightweight JSON persistence without adding Room to the small AAS APK. */
class AutomationRepository(context: Context) {
    private val storageContext = context.applicationContext.let { app ->
        if (android.os.Build.VERSION.SDK_INT >= 24) app.createDeviceProtectedStorageContext() else app
    }
    private val prefs = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_MASTER_ENABLED, value).apply() }

    fun listRules(): List<AutomationRule> = synchronized(lock) {
        decodeRules(prefs.getString(KEY_RULES, null)).sortedBy { it.createdAt }
    }

    fun getRule(id: String): AutomationRule? = listRules().firstOrNull { it.id == id }

    fun saveRule(rule: AutomationRule) = synchronized(lock) {
        val rules = decodeRules(prefs.getString(KEY_RULES, null)).toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules += rule
        writeRules(rules)
    }

    fun deleteRule(id: String) = synchronized(lock) {
        writeRules(decodeRules(prefs.getString(KEY_RULES, null)).filterNot { it.id == id })
    }

    fun duplicateRule(id: String): AutomationRule? = synchronized(lock) {
        val source = decodeRules(prefs.getString(KEY_RULES, null)).firstOrNull { it.id == id } ?: return null
        val copy = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = source.name + " — копия",
            enabled = false,
            lastRunAt = 0L,
            runCount = 0,
            createdAt = System.currentTimeMillis(),
        )
        val rules = decodeRules(prefs.getString(KEY_RULES, null)).toMutableList().apply { add(copy) }
        writeRules(rules)
        copy
    }

    fun updateRunStats(id: String, at: Long) = synchronized(lock) {
        val rules = decodeRules(prefs.getString(KEY_RULES, null)).map {
            if (it.id == id) it.copy(lastRunAt = at, runCount = it.runCount + 1) else it
        }
        writeRules(rules)
    }

    fun addLog(log: AutomationLog) = synchronized(lock) {
        val logs = decodeLogs(prefs.getString(KEY_LOGS, null)).toMutableList()
        logs.add(0, log)
        while (logs.size > MAX_LOGS) logs.removeAt(logs.lastIndex)
        prefs.edit().putString(KEY_LOGS, JSONArray().apply { logs.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun listLogs(): List<AutomationLog> = synchronized(lock) {
        decodeLogs(prefs.getString(KEY_LOGS, null))
    }

    fun clearLogs() = prefs.edit().remove(KEY_LOGS).apply()

    private fun writeRules(rules: List<AutomationRule>) {
        prefs.edit().putString(KEY_RULES, JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()).commit()
    }

    private fun decodeRules(raw: String?): List<AutomationRule> = try {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(AutomationRule.fromJson(it)) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun decodeLogs(raw: String?): List<AutomationLog> = try {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(AutomationLog.fromJson(it)) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val FILE_NAME = "aas_automations"
        private const val KEY_RULES = "rules_v2"
        private const val KEY_LOGS = "logs_v2"
        private const val KEY_MASTER_ENABLED = "master_enabled"
        private const val MAX_LOGS = 150
    }
}
