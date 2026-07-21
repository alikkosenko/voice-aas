package com.aas.app.automation

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aas.app.R
import com.aas.app.accessibility.AasAccessibilityService
import com.aas.app.databinding.ActivityAutomationsBinding
import com.aas.app.runtime.AasRuntime
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutomationsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAutomationsBinding
    private lateinit var repository: AutomationRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AasRuntime.requireInitialized(this)
        repository = AasRuntime.automationRepository
        binding = ActivityAutomationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonBack.setOnClickListener { finish() }
        binding.switchAutomationMaster.isChecked = repository.masterEnabled
        binding.switchAutomationMaster.setOnCheckedChangeListener { _, checked ->
            repository.masterEnabled = checked
            renderRules()
        }
        binding.buttonCreateAutomation.setOnClickListener { showRuleEditor(null) }
        binding.buttonTemplates.setOnClickListener { showTemplates() }
        binding.buttonJournal.setOnClickListener { showJournal() }
        renderRules()
    }

    override fun onResume() {
        super.onResume()
        renderRules()
    }

    override fun onDestroy() {
        AasAccessibilityService.learnMode = false
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun renderRules() {
        val rules = repository.listRules()
        binding.textAutomationEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        binding.automationRulesContainer.removeAllViews()
        rules.forEach { rule -> binding.automationRulesContainer.addView(createRuleCard(rule)) }
    }

    private fun createRuleCard(rule: AutomationRule): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.aas_divider))
            setCardBackgroundColor(getColor(R.color.aas_surface))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = rule.name
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.aas_text))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val enabled = SwitchMaterial(this).apply {
            isChecked = rule.enabled
            isEnabled = repository.masterEnabled
            setOnCheckedChangeListener { _, checked ->
                repository.saveRule(rule.copy(enabled = checked))
                renderRules()
            }
        }
        header.addView(title)
        header.addView(enabled)
        content.addView(header)

        content.addView(TextView(this).apply {
            text = triggerSummary(rule.trigger)
            textSize = 14f
            setTextColor(getColor(R.color.aas_text_secondary))
        })
        content.addView(TextView(this).apply {
            val last = if (rule.lastRunAt > 0) dateFormat.format(Date(rule.lastRunAt)) else localized("никогда", "ніколи")
            text = localized(
                "Действий: ${rule.actions.size} • запусков: ${rule.runCount} • последний: $last",
                "Дій: ${rule.actions.size} • запусків: ${rule.runCount} • останній: $last",
            )
            textSize = 12f
            setTextColor(getColor(R.color.aas_text_secondary))
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        buttons.addView(smallButton(localized("Тест", "Тест")) {
            AasRuntime.automation.runNow(rule.id) { result ->
                runOnUiThread {
                    Toast.makeText(this, result.spokenMessage, Toast.LENGTH_LONG).show()
                    renderRules()
                }
            }
        })
        buttons.addView(smallButton(localized("Изменить", "Змінити")) { showRuleEditor(rule) })
        buttons.addView(smallButton(localized("Копия", "Копія")) {
            repository.duplicateRule(rule.id)
            renderRules()
        })
        buttons.addView(smallButton(localized("Удалить", "Видалити")) {
            MaterialAlertDialogBuilder(this)
                .setTitle(localized("Удалить автоматизацию?", "Видалити автоматизацію?"))
                .setMessage(rule.name)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(localized("Удалить", "Видалити")) { _, _ ->
                    repository.deleteRule(rule.id)
                    renderRules()
                }
                .show()
        })
        content.addView(buttons)
        card.addView(content)
        return card
    }

    private fun smallButton(label: String, action: () -> Unit): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = label
        textSize = 11f
        minWidth = 0
        minimumWidth = 0
        insetTop = 0
        insetBottom = 0
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
            marginStart = dp(5)
        }
        setOnClickListener { action() }
    }

    private data class TriggerViews(
        val timePicker: TimePicker? = null,
        val dayChecks: List<CheckBox> = emptyList(),
        val keyEdit: EditText? = null,
        val thresholdEdit: EditText? = null,
    )

    private data class ActionRow(
        val root: LinearLayout,
        val typeSpinner: Spinner,
        val valueEdit: EditText,
        val pickAppButton: MaterialButton,
    )

    private fun showRuleEditor(existing: AutomationRule?) {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        scroll.addView(body)

        body.addView(sectionLabel(localized("Название", "Назва")))
        val nameEdit = EditText(this).apply {
            text = existing?.name.orEmpty()
            hint = localized("Например: утренний климат", "Наприклад: ранковий клімат")
            maxLines = 1
        }
        body.addView(nameEdit)

        body.addView(sectionLabel(localized("КОГДА", "КОЛИ")))
        val triggerTypes = AutomationTriggerType.entries
        val triggerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AutomationsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                triggerTypes.map(::triggerLabel),
            )
            setSelection(triggerTypes.indexOf(existing?.trigger?.type ?: AutomationTriggerType.STARTUP))
        }
        body.addView(triggerSpinner)
        val triggerConfig = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(triggerConfig)
        var triggerViews = TriggerViews()

        fun renderTriggerConfig(type: AutomationTriggerType) {
            triggerConfig.removeAllViews()
            triggerViews = when (type) {
                AutomationTriggerType.SCHEDULE -> {
                    val time = TimePicker(this).apply {
                        setIs24HourView(true)
                        hour = existing?.trigger?.hour ?: 8
                        minute = existing?.trigger?.minute ?: 0
                    }
                    triggerConfig.addView(time)
                    triggerConfig.addView(TextView(this).apply { text = localized("Дни недели (ничего = каждый день)", "Дні тижня (нічого = щодня)") })
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val labels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                    val checks = labels.mapIndexed { index, label ->
                        CheckBox(this).apply {
                            text = label
                            isChecked = existing?.trigger?.daysMask?.let { it and (1 shl index) != 0 } ?: false
                            row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        }
                    }
                    triggerConfig.addView(row)
                    TriggerViews(timePicker = time, dayChecks = checks)
                }
                AutomationTriggerType.BUTTON -> {
                    val keyEdit = EditText(this).apply {
                        hint = "keyCode"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        text = existing?.trigger?.keyCode?.takeIf { it > 0 }?.toString().orEmpty()
                    }
                    val capture = MaterialButton(this).apply {
                        text = localized("Нажать и запомнить кнопку", "Натиснути й запам’ятати кнопку")
                        setOnClickListener { beginButtonCapture(keyEdit) }
                    }
                    triggerConfig.addView(keyEdit)
                    triggerConfig.addView(capture)
                    triggerConfig.addView(TextView(this).apply {
                        text = localized(
                            "Кнопка будет занята автоматизацией и не передастся штатной системе.",
                            "Кнопку буде зайнято автоматизацією, вона не передаватиметься штатній системі.",
                        )
                        textSize = 12f
                    })
                    TriggerViews(keyEdit = keyEdit)
                }
                AutomationTriggerType.SOC_BELOW, AutomationTriggerType.SOC_ABOVE -> {
                    val threshold = EditText(this).apply {
                        hint = localized("Порог заряда, %", "Поріг заряду, %")
                        inputType = InputType.TYPE_CLASS_NUMBER
                        text = (existing?.trigger?.threshold ?: 20).toString()
                    }
                    triggerConfig.addView(threshold)
                    TriggerViews(thresholdEdit = threshold)
                }
                else -> TriggerViews()
            }
        }
        triggerSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            renderTriggerConfig(triggerTypes[position])
        }
        renderTriggerConfig(triggerTypes[triggerSpinner.selectedItemPosition])

        body.addView(sectionLabel(localized("ТОГДА", "ТОДІ")))
        val actionContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(actionContainer)
        val actionRows = mutableListOf<ActionRow>()

        fun addActionRow(action: AutomationAction = AutomationAction(AutomationActionType.COMMAND, "")) {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(5), 0, dp(8))
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val types = AutomationActionType.entries
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@AutomationsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    types.map(::actionLabel),
                )
                setSelection(types.indexOf(action.type))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "×"
                minWidth = 0
                minimumWidth = 0
            }
            top.addView(spinner)
            top.addView(remove)
            root.addView(top)
            val value = EditText(this).apply {
                text = action.value
                minLines = 1
                maxLines = 3
            }
            root.addView(value)
            val pickApp = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = localized("Выбрать приложение", "Вибрати застосунок")
                visibility = View.GONE
                setOnClickListener { showInstalledAppPicker { packageName -> value.text = packageName } }
            }
            root.addView(pickApp)
            val row = ActionRow(root, spinner, value, pickApp)
            actionRows += row
            actionContainer.addView(root)

            fun updateHint(type: AutomationActionType) {
                value.hint = when (type) {
                    AutomationActionType.COMMAND -> localized("Встроенная команда: включи климат на 22", "Вбудована команда: увімкни клімат на 22")
                    AutomationActionType.DELAY -> localized("Задержка в миллисекундах, например 1000", "Затримка в мілісекундах, наприклад 1000")
                    AutomationActionType.SPEAK -> localized("Текст для TTS", "Текст для TTS")
                    AutomationActionType.NOTIFICATION -> localized("Текст уведомления", "Текст сповіщення")
                    AutomationActionType.LAUNCH_APP -> localized("Пакет приложения", "Пакет застосунку")
                    AutomationActionType.OPEN_URL -> "https://… или geo:…"
                }
                value.inputType = if (type == AutomationActionType.DELAY) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                pickApp.visibility = if (type == AutomationActionType.LAUNCH_APP) View.VISIBLE else View.GONE
            }
            spinner.onItemSelectedListener = SimpleItemSelectedListener { position -> updateHint(types[position]) }
            updateHint(action.type)
            remove.setOnClickListener {
                actionRows.remove(row)
                actionContainer.removeView(root)
            }
        }

        (existing?.actions?.takeIf { it.isNotEmpty() } ?: listOf(AutomationAction(AutomationActionType.COMMAND, "")))
            .forEach(::addActionRow)
        body.addView(MaterialButton(this).apply {
            text = localized("+ Добавить действие", "+ Додати дію")
            setOnClickListener { addActionRow() }
        })

        body.addView(sectionLabel(localized("НАСТРОЙКИ", "НАЛАШТУВАННЯ")))
        val cooldown = EditText(this).apply {
            hint = localized("Пауза между запусками, секунд", "Пауза між запусками, секунд")
            inputType = InputType.TYPE_CLASS_NUMBER
            text = (existing?.cooldownSeconds ?: 60).toString()
        }
        val stopOnError = CheckBox(this).apply {
            text = localized("Остановить цепочку при ошибке", "Зупинити ланцюжок у разі помилки")
            isChecked = existing?.stopOnError ?: true
        }
        val stationary = CheckBox(this).apply {
            text = localized("Выполнять только на остановленной машине", "Виконувати лише на зупиненому автомобілі")
            isChecked = existing?.requireStationary ?: false
        }
        val oncePerBoot = CheckBox(this).apply {
            text = localized("Не более одного раза после запуска AAS", "Не більше одного разу після запуску AAS")
            isChecked = existing?.runOncePerBoot ?: false
        }
        val speakResult = CheckBox(this).apply {
            text = localized("Озвучить итог автоматизации", "Озвучити підсумок автоматизації")
            isChecked = existing?.speakResult ?: false
        }
        body.addView(cooldown)
        body.addView(stopOnError)
        body.addView(stationary)
        body.addView(oncePerBoot)
        body.addView(speakResult)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) localized("Новая автоматизация", "Нова автоматизація") else localized("Изменить автоматизацию", "Змінити автоматизацію"))
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(localized("Сохранить", "Зберегти"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                val type = triggerTypes[triggerSpinner.selectedItemPosition]
                val actions = actionRows.mapNotNull { row ->
                    val actionType = AutomationActionType.entries[row.typeSpinner.selectedItemPosition]
                    row.valueEdit.text.toString().trim().takeIf { it.isNotBlank() }
                        ?.let { AutomationAction(actionType, it) }
                }
                val trigger = AutomationTrigger(
                    type = type,
                    hour = triggerViews.timePicker?.hour ?: 8,
                    minute = triggerViews.timePicker?.minute ?: 0,
                    daysMask = triggerViews.dayChecks.foldIndexed(0) { index, mask, check -> if (check.isChecked) mask or (1 shl index) else mask },
                    keyCode = triggerViews.keyEdit?.text?.toString()?.toIntOrNull() ?: 0,
                    threshold = triggerViews.thresholdEdit?.text?.toString()?.toIntOrNull()?.coerceIn(0, 100) ?: 20,
                )
                val error = validateRule(name, trigger, actions)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                repository.saveRule(
                    AutomationRule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        enabled = existing?.enabled ?: true,
                        trigger = trigger,
                        actions = actions,
                        cooldownSeconds = cooldown.text.toString().toIntOrNull()?.coerceIn(0, 86_400) ?: 60,
                        stopOnError = stopOnError.isChecked,
                        requireStationary = stationary.isChecked,
                        runOncePerBoot = oncePerBoot.isChecked,
                        speakResult = speakResult.isChecked,
                        lastRunAt = existing?.lastRunAt ?: 0L,
                        runCount = existing?.runCount ?: 0,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    )
                )
                dialog.dismiss()
                renderRules()
            }
        }
        dialog.setOnDismissListener { AasAccessibilityService.learnMode = false }
        dialog.show()
    }

    private fun validateRule(name: String, trigger: AutomationTrigger, actions: List<AutomationAction>): String? {
        if (name.isBlank()) return localized("Введите название", "Введіть назву")
        if (actions.isEmpty()) return localized("Добавьте хотя бы одно действие", "Додайте хоча б одну дію")
        if (trigger.type == AutomationTriggerType.BUTTON && trigger.keyCode <= 0) {
            return localized("Выберите аппаратную кнопку", "Виберіть апаратну кнопку")
        }
        return null
    }

    private fun beginButtonCapture(target: EditText) {
        if (!AasAccessibilityService.isConnected) {
            MaterialAlertDialogBuilder(this)
                .setTitle(localized("Accessibility не подключён", "Accessibility не підключено"))
                .setMessage(localized("Откройте системные настройки и включите службу AAS.", "Відкрийте системні налаштування та увімкніть службу AAS."))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(localized("Открыть настройки", "Відкрити налаштування")) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
            return
        }
        AasAccessibilityService.capturedKey.value = null
        AasAccessibilityService.learnMode = true
        Toast.makeText(this, localized("Нажмите нужную кнопку", "Натисніть потрібну кнопку"), Toast.LENGTH_LONG).show()
        val started = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                val captured = AasAccessibilityService.capturedKey.value
                if (captured != null) {
                    AasAccessibilityService.capturedKey.value = null
                    if (captured.assignable) {
                        target.text = captured.keyCode.toString()
                        Toast.makeText(this@AutomationsActivity, "keyCode ${captured.keyCode}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@AutomationsActivity, localized("Эту кнопку назначать нельзя", "Цю кнопку не можна призначити"), Toast.LENGTH_LONG).show()
                    }
                    return
                }
                if (System.currentTimeMillis() - started >= 20_000L) {
                    AasAccessibilityService.learnMode = false
                    Toast.makeText(this@AutomationsActivity, localized("Кнопка не получена", "Кнопку не отримано"), Toast.LENGTH_SHORT).show()
                    return
                }
                mainHandler.postDelayed(this, 150L)
            }
        }
        mainHandler.post(poll)
    }

    private fun showInstalledAppPicker(onSelected: (String) -> Unit) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        MaterialAlertDialogBuilder(this)
            .setTitle(localized("Выберите приложение", "Виберіть застосунок"))
            .setItems(apps.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, which -> onSelected(apps[which].second) }
            .show()
    }

    private fun showTemplates() {
        val templates = listOf(
            localized("Будни 08:00 — климат 22°", "Будні 08:00 — клімат 22°") to AutomationRule(
                name = localized("Утренний комфорт", "Ранковий комфорт"), enabled = false,
                trigger = AutomationTrigger(AutomationTriggerType.SCHEDULE, hour = 8, minute = 0, daysMask = 0b0011111),
                actions = listOf(
                    AutomationAction(AutomationActionType.COMMAND, localized("включи климат", "увімкни клімат")),
                    AutomationAction(AutomationActionType.COMMAND, localized("поставь температуру 22 градуса", "встанови температуру 22 градуси")),
                    AutomationAction(AutomationActionType.COMMAND, localized("поставь обдув на второй уровень", "встанови обдув на другий рівень")),
                ), requireStationary = true,
            ),
            localized("Заряд ниже 20% — уведомление", "Заряд нижче 20% — сповіщення") to AutomationRule(
                name = localized("Низкий заряд", "Низький заряд"), enabled = false,
                trigger = AutomationTrigger(AutomationTriggerType.SOC_BELOW, threshold = 20),
                actions = listOf(AutomationAction(AutomationActionType.NOTIFICATION, localized("Заряд батареи опустился ниже 20%", "Заряд батареї опустився нижче 20%"))),
                cooldownSeconds = 3600,
            ),
            localized("Интернет появился — открыть музыку", "Інтернет з’явився — відкрити музику") to AutomationRule(
                name = localized("Музыка после подключения", "Музика після підключення"), enabled = false,
                trigger = AutomationTrigger(AutomationTriggerType.INTERNET_AVAILABLE),
                actions = listOf(AutomationAction(AutomationActionType.COMMAND, localized("включи музыку", "увімкни музику"))),
                cooldownSeconds = 300,
            ),
            localized("Подключено питание — климат 22°", "Підключено живлення — клімат 22°") to AutomationRule(
                name = localized("Климат при питании", "Клімат при живленні"), enabled = false,
                trigger = AutomationTrigger(AutomationTriggerType.POWER_CONNECTED),
                actions = listOf(
                    AutomationAction(AutomationActionType.COMMAND, localized("включи климат", "увімкни клімат")),
                    AutomationAction(AutomationActionType.COMMAND, localized("поставь температуру 22 градуса", "встанови температуру 22 градуси")),
                ), requireStationary = true,
            ),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(localized("Шаблоны автоматизаций", "Шаблони автоматизацій"))
            .setItems(templates.map { it.first }.toTypedArray()) { _, which ->
                repository.saveRule(templates[which].second)
                Toast.makeText(this, localized("Шаблон добавлен выключенным", "Шаблон додано вимкненим"), Toast.LENGTH_LONG).show()
                renderRules()
            }
            .show()
    }

    private fun showJournal() {
        val logs = repository.listLogs()
        val text = if (logs.isEmpty()) localized("Журнал пуст", "Журнал порожній") else logs.joinToString("\n\n") {
            val status = if (it.success) "✓" else "✕"
            "$status ${dateFormat.format(Date(it.timestamp))}\n${it.ruleName} • ${it.source}\n${it.message.take(800)}"
        }
        val view = TextView(this).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            this.text = text
            setTextIsSelectable(true)
            textSize = 13f
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(localized("Журнал автоматизаций", "Журнал автоматизацій"))
            .setView(ScrollView(this).apply { addView(view) })
            .setNegativeButton(localized("Закрыть", "Закрити"), null)
            .setNeutralButton(localized("Очистить", "Очистити")) { _, _ ->
                repository.clearLogs()
            }
            .show()
    }

    private fun triggerSummary(trigger: AutomationTrigger): String = when (trigger.type) {
        AutomationTriggerType.STARTUP -> localized("При запуске AAS", "Під час запуску AAS")
        AutomationTriggerType.SCHEDULE -> {
            val days = daySummary(trigger.daysMask)
            localized("По расписанию ${"%02d:%02d".format(trigger.hour, trigger.minute)} • $days", "За розкладом ${"%02d:%02d".format(trigger.hour, trigger.minute)} • $days")
        }
        AutomationTriggerType.INTERNET_AVAILABLE -> localized("Когда интернет снова доступен", "Коли інтернет знову доступний")
        AutomationTriggerType.BUTTON -> localized("Аппаратная кнопка keyCode ${trigger.keyCode}", "Апаратна кнопка keyCode ${trigger.keyCode}")
        AutomationTriggerType.POWER_CONNECTED -> localized("При подключении питания", "Під час підключення живлення")
        AutomationTriggerType.POWER_DISCONNECTED -> localized("При отключении питания", "Під час відключення живлення")
        AutomationTriggerType.SCREEN_ON -> localized("При включении экрана", "Під час увімкнення екрана")
        AutomationTriggerType.SCREEN_OFF -> localized("При выключении экрана", "Під час вимкнення екрана")
        AutomationTriggerType.SOC_BELOW -> localized("Когда заряд пересекает ${trigger.threshold}% вниз", "Коли заряд перетинає ${trigger.threshold}% вниз")
        AutomationTriggerType.SOC_ABOVE -> localized("Когда заряд пересекает ${trigger.threshold}% вверх", "Коли заряд перетинає ${trigger.threshold}% вгору")
    }

    private fun triggerLabel(type: AutomationTriggerType): String = when (type) {
        AutomationTriggerType.STARTUP -> localized("Запуск AAS", "Запуск AAS")
        AutomationTriggerType.SCHEDULE -> localized("Время / расписание", "Час / розклад")
        AutomationTriggerType.INTERNET_AVAILABLE -> localized("Интернет восстановлен", "Інтернет відновлено")
        AutomationTriggerType.BUTTON -> localized("Аппаратная кнопка", "Апаратна кнопка")
        AutomationTriggerType.POWER_CONNECTED -> localized("Питание подключено", "Живлення підключено")
        AutomationTriggerType.POWER_DISCONNECTED -> localized("Питание отключено", "Живлення відключено")
        AutomationTriggerType.SCREEN_ON -> localized("Экран включён", "Екран увімкнено")
        AutomationTriggerType.SCREEN_OFF -> localized("Экран выключен", "Екран вимкнено")
        AutomationTriggerType.SOC_BELOW -> localized("Заряд ниже порога", "Заряд нижче порога")
        AutomationTriggerType.SOC_ABOVE -> localized("Заряд выше порога", "Заряд вище порога")
    }

    private fun actionLabel(type: AutomationActionType): String = when (type) {
        AutomationActionType.COMMAND -> localized("Команда AAS", "Команда AAS")
        AutomationActionType.DELAY -> localized("Задержка", "Затримка")
        AutomationActionType.SPEAK -> localized("Озвучить текст", "Озвучити текст")
        AutomationActionType.NOTIFICATION -> localized("Уведомление", "Сповіщення")
        AutomationActionType.LAUNCH_APP -> localized("Открыть приложение", "Відкрити застосунок")
        AutomationActionType.OPEN_URL -> localized("Открыть ссылку", "Відкрити посилання")
    }

    private fun daySummary(mask: Int): String {
        if (mask == 0) return localized("каждый день", "щодня")
        val labels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        return labels.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.joinToString(", ")
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun localized(russian: String, ukrainian: String): String =
        if (AasRuntime.prefs.languageTag.startsWith("uk", ignoreCase = true)) ukrainian else russian

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

private class SimpleItemSelectedListener(
    private val selected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
