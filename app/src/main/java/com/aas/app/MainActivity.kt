package com.aas.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.aas.app.accessibility.AasAccessibilityService
import com.aas.app.runtime.AasRuntime
import com.aas.app.service.VoiceReadyService
import com.aas.app.vehicle.BydWriteAllowlist
import com.aas.app.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private var suppressWriteSwitchCallback = false
    private var suppressNativeAssistantCallback = false
    private var suppressVoiceResponsesCallback = false

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshStatus()
            if (!granted) {
                Toast.makeText(this, getString(R.string.microphone_required), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPrefs(this)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(prefs.languageTag))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AasRuntime.requireInitialized(this)
        val readyService = Intent(this, VoiceReadyService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(this, readyService)
        else startService(readyService)

        prefs.sharedPreferences().registerOnSharedPreferenceChangeListener(this)

        binding.switchEnabled.isChecked = prefs.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, checked -> prefs.enabled = checked }
        binding.switchDisableNativeAssistant.setOnCheckedChangeListener { _, checked ->
            if (suppressNativeAssistantCallback) return@setOnCheckedChangeListener
            setNativeAssistantDisabled(checked)
        }

        setVoiceResponsesSwitch(prefs.voiceResponsesEnabled)
        suppressNativeAssistantCallback = true
        binding.switchDisableNativeAssistant.isChecked = prefs.nativeAssistantDisabled
        suppressNativeAssistantCallback = false
        binding.switchVoiceResponses.setOnCheckedChangeListener { _, checked ->
            if (suppressVoiceResponsesCallback) return@setOnCheckedChangeListener
            AasRuntime.voice.setVoiceResponsesEnabled(checked)
            setVoiceResponsesSwitch(prefs.voiceResponsesEnabled)
            if (checked) restoreSpeechOutput(runTest = false, showToast = false)
        }

        binding.switchVehicleWrites.isChecked = prefs.vehicleWritesEnabled
        binding.switchVehicleWrites.setOnCheckedChangeListener { _, checked ->
            if (suppressWriteSwitchCallback) return@setOnCheckedChangeListener
            if (!checked) {
                prefs.vehicleWritesEnabled = false
                return@setOnCheckedChangeListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.allow_vehicle_title)
                .setMessage(R.string.allow_vehicle_message)
                .setNegativeButton(R.string.cancel) { _, _ -> setVehicleWriteSwitch(false) }
                .setPositiveButton(R.string.allow) { _, _ ->
                    prefs.vehicleWritesEnabled = true
                    setVehicleWriteSwitch(true)
                }
                .setOnCancelListener { setVehicleWriteSwitch(false) }
                .show()
        }

        if (prefs.languageTag == "uk-UA") binding.languageUkrainian.isChecked = true
        binding.languageGroup.setOnCheckedChangeListener { _, checkedId ->
            val newTag = if (checkedId == binding.languageUkrainian.id) "uk-UA" else "ru-RU"
            if (prefs.languageTag != newTag) {
                prefs.languageTag = newTag
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newTag))
                AasRuntime.voice.reinitializeTts()
                AasRuntime.voice.preload()
            }
        }

        binding.buttonGrantMicrophone.setOnClickListener {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.buttonOpenAccessibility.setOnClickListener { enableAccessibilityThroughHelper() }

        binding.buttonConnectAdb.setOnClickListener { connectAdbHelper() }
        binding.buttonRefreshVin.setOnClickListener { refreshVin() }

        binding.buttonShowCommands.setOnClickListener { showVoiceCommands() }
        binding.buttonTestSpeech.setOnClickListener {
            restoreSpeechOutput(runTest = true, showToast = true)
        }
        binding.buttonSelectMusicApp.setOnClickListener {
            showAppPicker(getString(R.string.music), prefs.musicPackage) { prefs.musicPackage = it }
        }
        binding.buttonSelectNavigationApp.setOnClickListener {
            showAppPicker(getString(R.string.navigation), prefs.navigationPackage) { prefs.navigationPackage = it }
        }
        binding.buttonSelectYoutubeApp.setOnClickListener {
            showAppPicker(getString(R.string.youtube), prefs.youtubePackage) { prefs.youtubePackage = it }
        }
        binding.buttonSelectRadioApp.setOnClickListener {
            showAppPicker(getString(R.string.radio), prefs.radioPackage) { prefs.radioPackage = it }
        }

        binding.buttonCaptureKey.setOnClickListener { beginSteeringButtonLearning() }

        binding.buttonTestVoice.setOnClickListener {
            if (!hasMicrophonePermission()) {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }
            startVoiceTest()
        }

        if (prefs.voiceResponsesEnabled) restoreSpeechOutput(runTest = false, showToast = false)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        destroyed = true
        prefs.sharedPreferences().unregisterOnSharedPreferenceChangeListener(this)
        AasAccessibilityService.learnMode = false
        AasAccessibilityService.capturedKey.value = null
        mainHandler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        runOnUiThread { refreshStatus() }
    }

    private fun setNativeAssistantDisabled(disabled: Boolean) {
        binding.switchDisableNativeAssistant.isEnabled = false
        ioExecutor.execute {
            val boot = AasRuntime.bootstrap.ensureRunning(enableAccessibility = true)
            val ok = boot.connected && AasRuntime.helper.setNativeAssistantDisabled(disabled)
            if (ok) prefs.nativeAssistantDisabled = disabled
            if (destroyed) return@execute
            runOnUiThread {
                binding.switchDisableNativeAssistant.isEnabled = true
                suppressNativeAssistantCallback = true
                binding.switchDisableNativeAssistant.isChecked = prefs.nativeAssistantDisabled
                suppressNativeAssistantCallback = false
                if (ok) AasRuntime.voice.reinitializeTts()
                val msg = when {
                    !ok -> getString(R.string.native_assistant_change_failed)
                    disabled -> getString(R.string.native_assistant_disabled_ok)
                    else -> getString(R.string.native_assistant_enabled_ok)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun connectAdbHelper() {
        binding.buttonConnectAdb.isEnabled = false
        binding.statusVehicle.text = getString(R.string.vehicle_connecting)
        ioExecutor.execute {
            val result = AasRuntime.bootstrap.ensureRunning(enableAccessibility = true)
            val serviceBound = if (result.accessibilityEnabled) waitForAccessibilityBinding() else false
            if (destroyed) return@execute
            runOnUiThread {
                binding.buttonConnectAdb.isEnabled = true
                val extra = result.adbFingerprint?.let { "\nADB key: $it" }.orEmpty()
                val status = if (result.connected && result.accessibilityEnabled && !serviceBound) {
                    result.message + getString(R.string.service_not_bound_suffix)
                } else result.message
                binding.statusVehicle.text = getString(R.string.vehicle_status, localized(status + extra))
                Toast.makeText(this, localized(status), Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun enableAccessibilityThroughHelper() {
        binding.buttonOpenAccessibility.isEnabled = false
        ioExecutor.execute {
            val result = AasRuntime.bootstrap.ensureRunning(enableAccessibility = true)
            val bound = result.accessibilityEnabled && waitForAccessibilityBinding()
            if (destroyed) return@execute
            runOnUiThread {
                binding.buttonOpenAccessibility.isEnabled = true
                val message = when {
                    bound -> getString(R.string.accessibility_service_ready)
                    result.connected -> result.message
                    else -> result.message
                }
                Toast.makeText(this, localized(message), Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun beginSteeringButtonLearning() {
        binding.buttonCaptureKey.isEnabled = false
        binding.buttonCaptureKey.text = getString(R.string.press_steering_button)
        ioExecutor.execute {
            val result = AasRuntime.bootstrap.ensureRunning(enableAccessibility = true)
            val bound = result.accessibilityEnabled && waitForAccessibilityBinding()
            if (destroyed) return@execute
            runOnUiThread {
                binding.buttonCaptureKey.isEnabled = true
                if (!bound) {
                    binding.buttonCaptureKey.text = getString(R.string.capture_steering_button)
                    Toast.makeText(this, localized(result.message), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                prefs.captureNextKey = true
                AasAccessibilityService.capturedKey.value = null
                AasAccessibilityService.learnMode = true
                Toast.makeText(this, getString(R.string.waiting_hardware_button), Toast.LENGTH_SHORT).show()
                pollCapturedButton(System.currentTimeMillis() + 20_000L)
            }
        }
    }

    private fun pollCapturedButton(deadline: Long) {
        if (destroyed) return
        val capture = AasAccessibilityService.capturedKey.value
        when {
            capture != null && capture.assignable -> {
                prefs.activationKeyCode = capture.keyCode
                prefs.captureNextKey = false
                prefs.lastResult = getString(R.string.key_saved_result, capture.keyCode)
                AasAccessibilityService.learnMode = false
                AasAccessibilityService.capturedKey.value = null
                Toast.makeText(this, getString(R.string.key_saved_toast, capture.keyCode), Toast.LENGTH_LONG).show()
                refreshStatus()
            }
            capture != null && !capture.assignable -> {
                AasAccessibilityService.capturedKey.value = null
                AasAccessibilityService.learnMode = true
                Toast.makeText(this, getString(R.string.key_not_assignable), Toast.LENGTH_LONG).show()
                mainHandler.postDelayed({ pollCapturedButton(deadline) }, 100L)
            }
            System.currentTimeMillis() >= deadline -> {
                prefs.captureNextKey = false
                AasAccessibilityService.learnMode = false
                binding.buttonCaptureKey.text = getString(R.string.capture_steering_button)
                Toast.makeText(this, getString(R.string.key_capture_timeout), Toast.LENGTH_LONG).show()
            }
            else -> mainHandler.postDelayed({ pollCapturedButton(deadline) }, 100L)
        }
    }

    private fun waitForAccessibilityBinding(): Boolean {
        repeat(30) {
            if (AasAccessibilityService.isConnected) return true
            Thread.sleep(200L)
        }
        return AasAccessibilityService.isConnected
    }

    private fun startVoiceTest() {
        AasRuntime.voice.toggle()
        binding.buttonTestVoice.text = getString(R.string.test_state, getString(R.string.state_speak))
        mainHandler.postDelayed({
            if (!destroyed) {
                binding.buttonTestVoice.text = getString(R.string.test_listen_once)
                refreshStatus()
            }
        }, 16_000L)
    }

    private fun refreshStatus() {
        binding.switchEnabled.isChecked = prefs.enabled
        setVoiceResponsesSwitch(prefs.voiceResponsesEnabled)
        suppressNativeAssistantCallback = true
        binding.switchDisableNativeAssistant.isChecked = prefs.nativeAssistantDisabled
        suppressNativeAssistantCallback = false
        setVehicleWriteSwitch(prefs.vehicleWritesEnabled)
        binding.textActivationKey.text = getString(R.string.activation_key, prefs.activationKeyCode)
        binding.textLastTranscript.text = prefs.lastTranscript
        binding.textLastResult.text = prefs.lastResult
        binding.buttonSelectMusicApp.text = getString(R.string.selected_music_app, selectedAppLabel(prefs.musicPackage))
        binding.buttonSelectNavigationApp.text = getString(R.string.selected_navigation_app, selectedAppLabel(prefs.navigationPackage))
        binding.buttonSelectYoutubeApp.text = getString(R.string.selected_youtube_app, selectedAppLabel(prefs.youtubePackage))
        binding.buttonSelectRadioApp.text = getString(R.string.selected_radio_app, selectedAppLabel(prefs.radioPackage))
        binding.buttonCaptureKey.text = if (AasAccessibilityService.learnMode || prefs.captureNextKey) {
            getString(R.string.press_steering_button)
        } else {
            getString(R.string.capture_steering_button)
        }

        binding.statusAccessibility.text = if (AasAccessibilityService.isConnected || isAccessibilityServiceEnabled()) {
            getString(R.string.accessibility_on)
        } else {
            getString(R.string.accessibility_off)
        }
        binding.statusMicrophone.text = if (hasMicrophonePermission()) {
            getString(R.string.microphone_on)
        } else {
            getString(R.string.microphone_off)
        }
        val recognizerBase = getString(if (prefs.languageTag.startsWith("uk")) R.string.recognizer_status_uk else R.string.recognizer_status_ru)
        binding.statusRecognizer.text = recognizerBase + getString(if (AasRuntime.voice.isModelReady()) R.string.recognizer_ready_suffix else R.string.recognizer_loading_suffix)

        binding.statusVehicle.text = getString(R.string.status_vehicle_checking)
        ioExecutor.execute {
            val status = AasRuntime.vehicle.diagnosticStatus()
            val vin = AasRuntime.vehicle.readVin()
            if (destroyed) return@execute
            runOnUiThread {
                binding.statusVehicle.text = getString(R.string.vehicle_status, localized(status))
                binding.textVehicleVin.text = if (vin != null) getString(R.string.vin_value, vin) else getString(R.string.vin_unavailable)
            }
        }
    }

    private fun refreshVin() {
        binding.buttonRefreshVin.isEnabled = false
        binding.textVehicleVin.text = getString(R.string.vin_checking)
        ioExecutor.execute {
            val vin = AasRuntime.vehicle.readVinEnsuringConnection()
            if (destroyed) return@execute
            runOnUiThread {
                binding.buttonRefreshVin.isEnabled = true
                binding.textVehicleVin.text = if (vin != null) getString(R.string.vin_value, vin) else getString(R.string.vin_unavailable)
            }
        }
    }



    private fun showAppPicker(title: String, selectedPackage: String, onSelected: (String) -> Unit) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, getString(R.string.apps_list_error), Toast.LENGTH_LONG).show()
            return
        }

        val labels: Array<CharSequence> = apps.map {
            "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" as CharSequence
        }.toTypedArray()
        val checked = apps.indexOfFirst { it.activityInfo.packageName == selectedPackage }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.choose_app_title, title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onSelected(apps[which].activityInfo.packageName)
                dialog.dismiss()
                refreshStatus()
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                onSelected("")
                refreshStatus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun selectedAppLabel(packageName: String): String {
        if (packageName.isBlank()) return getString(R.string.auto_select)
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun showVoiceCommands(forceUkrainian: Boolean? = null) {
        val ukrainian = forceUkrainian ?: prefs.languageTag.startsWith("uk", ignoreCase = true)
        val commands = if (ukrainian) ukrainianCommandHelp() else russianCommandHelp()
        val density = resources.displayMetrics.density
        val horizontal = (22 * density).toInt()
        val vertical = (12 * density).toInt()
        val messageView = TextView(this).apply {
            text = commands
            textSize = 15f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.08f)
            setPadding(horizontal, vertical, horizontal, vertical)
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(messageView)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (ukrainian) R.string.voice_commands_title_uk else R.string.voice_commands_title_ru)
            .setView(scrollView)
            .setNeutralButton(if (ukrainian) R.string.show_russian_commands else R.string.show_ukrainian_commands) { _, _ ->
                showVoiceCommands(!ukrainian)
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun russianCommandHelp(): String = ("""
КЛИМАТ
• «Включи климат» / «Выключи климат»
• «Авто климат» / «Выключи авто климат»
• «Температура 22 градуса»
• «Вентилятор 3» или «Обдув салона 3»
• «Рециркуляция» / «Воздух с улицы»
• «Обогрев заднего стекла» / «Выключи обогрев зеркал»

СИДЕНЬЯ И РУЛЬ
• «Обогрев водительского / пассажирского сиденья 1–5»
• «Вентиляция водительского / пассажирского сиденья 1–5»
• «Обогрев всех сидений минимум / средний / максимум» (1 / 3 / 5)
• «Вентиляция всех сидений минимум / средний / максимум» (1 / 3 / 5)
• «Выключи обогрев / вентиляцию водительского, пассажирского или всех сидений»
• «Подогрев руля 1–3» / «Выключи подогрев руля»
Примечание: «все сиденья» — водительское и переднее пассажирское.

ОКНА, ЛЮК И ШТОРКА
• «Открой / закрой водительское или пассажирское окно»
• «Открой / закрой заднее левое или заднее правое окно»
• «Открой / закрой передние, задние или все окна»
• «Установи водительское окно на 40 процентов» — поддерживаются все окна и группы, 0–100%
• «Открой люк» / «Закрой люк» / «Люк стоп»
• «Люк проветривание» / «Люк комфорт» / «Приподними люк»
• «Открой шторку» / «Закрой шторку»

ДВЕРИ И БАГАЖНИКИ
• «Закрой двери» / «Открой двери»
• «Открой передний багажник» / «Закрой передний багажник»
• «Открой багажник» / «Закрой багажник»

ОСВЕЩЕНИЕ
• «Включи свет в салоне» / «Выключи свет в салоне»
• «Включи подсветку салона» / «Выключи подсветку салона»
• «Включи дневные огни» / «Выключи дневные огни»

ХОЛОДИЛЬНИК
• «Включи холодильник» / «Выключи холодильник»
• «Холодильник минус 6 градусов»
• «Холодильник 5 градусов»
• «Холодильник 40 градусов»
Охлаждение: −6…+15 °C. Нагрев: 35…50 °C.

ИНФОРМАЦИЯ ОБ АВТОМОБИЛЕ
• «Сколько заряда» / «Уровень заряда»
• «Давление в шинах»
• «Какой запас хода» — распознаётся, но пока не выполняется.

СИСТЕМА И МУЛЬТИМЕДИА
• «Включи Bluetooth» / «Выключи Bluetooth»
• «Включи Wi-Fi» / «Выключи Wi-Fi»
• «Громкость 8» — точный системный уровень
• «Громкость 50 процентов» — процент от максимума
• «Громче на 3» / «Тише на 2» — изменение на указанное число уровней
• «Выключи звук» / «Включи звук»
• «Пауза» / «Следующий трек» / «Предыдущий трек»
• «Открой музыку» / «Открой YouTube» / «Открой радио»
• «YouTube Linkin Park» / «Ютуб обзор BYD Seal» — поиск в выбранном YouTube ReVanced
• «Найди на YouTube ремонт подвески» — альтернативная форма поиска
• «На главный экран»

НАВИГАЦИЯ
• «Построй маршрут до Дерибасовской»
• «Проложи маршрут в Киев»
• «Поехали домой / на работу / в офис»
• «Найди ближайшую зарядку / заправку / СТО / шиномонтаж / парковку»
• «Повтори последний маршрут»
• «Открой навигатор»
""".trimIndent() + "\n\n" + technicalCommandHelp(ukrainian = false))

    private fun ukrainianCommandHelp(): String = ("""
КЛІМАТ
• «Увімкни клімат» / «Вимкни клімат»
• «Авто клімат» / «Вимкни авто клімат»
• «Температура 22 градуси»
• «Вентилятор 3» або «Обдув салону 3»
• «Рециркуляція» / «Повітря з вулиці»
• «Обігрів заднього скла» / «Вимкни обігрів дзеркал»

СИДІННЯ ТА КЕРМО
• «Підігрів водійського / пасажирського сидіння 1–5»
• «Вентиляція водійського / пасажирського сидіння 1–5»
• «Підігрів усіх сидінь мінімум / середній / максимум» (1 / 3 / 5)
• «Вентиляція всіх сидінь мінімум / середній / максимум» (1 / 3 / 5)
• «Вимкни підігрів / вентиляцію водійського, пасажирського або всіх сидінь»
• «Підігрів керма 1–3» / «Вимкни підігрів керма»
Примітка: «усі сидіння» — водійське та переднє пасажирське.

ВІКНА, ЛЮК І ШТОРКА
• «Відкрий / закрий водійське або пасажирське вікно»
• «Відкрий / закрий заднє ліве або заднє праве вікно»
• «Відкрий / закрий передні, задні або всі вікна»
• «Встанови водійське вікно на 40 відсотків» — підтримуються всі вікна та групи, 0–100%
• «Відкрий люк» / «Закрий люк» / «Люк стоп»
• «Люк провітрювання» / «Люк комфорт» / «Підніми люк»
• «Відкрий шторку» / «Закрий шторку»

ДВЕРІ ТА БАГАЖНИКИ
• «Закрий двері» / «Відкрий двері»
• «Відкрий передній багажник» / «Закрий передній багажник»
• «Відкрий багажник» / «Закрий багажник»

ОСВІТЛЕННЯ
• «Увімкни світло в салоні» / «Вимкни світло в салоні»
• «Увімкни підсвічування салону» / «Вимкни підсвічування салону»
• «Увімкни денні ходові вогні» / «Вимкни денні ходові вогні»

ХОЛОДИЛЬНИК
• «Увімкни холодильник» / «Вимкни холодильник»
• «Холодильник мінус 6 градусів»
• «Холодильник 5 градусів»
• «Холодильник 40 градусів»
Охолодження: −6…+15 °C. Нагрівання: 35…50 °C.

ІНФОРМАЦІЯ ПРО АВТОМОБІЛЬ
• «Скільки заряду» / «Рівень заряду»
• «Тиск у шинах»
• «Який запас ходу» — розпізнається, але поки не виконується.

СИСТЕМА ТА МУЛЬТИМЕДІА
• «Увімкни Bluetooth» / «Вимкни Bluetooth»
• «Увімкни Wi-Fi» / «Вимкни Wi-Fi»
• «Гучність 8» — точний системний рівень
• «Гучність 50 відсотків» — відсоток від максимуму
• «Гучніше на 3» / «Тихіше на 2» — зміна на вказану кількість рівнів
• «Вимкни звук» / «Увімкни звук»
• «Пауза» / «Наступний трек» / «Попередній трек»
• «Відкрий музику» / «Відкрий YouTube» / «Відкрий радіо»
• «YouTube Linkin Park» / «Ютуб огляд BYD Seal» — пошук у вибраному YouTube ReVanced
• «Знайди на YouTube ремонт підвіски» — альтернативна форма пошуку
• «На головний екран»

НАВІГАЦІЯ
• «Побудуй маршрут до Дерибасівської»
• «Проклади маршрут у Київ»
• «Поїхали додому / на роботу / в офіс»
• «Знайди найближчу зарядку / заправку / СТО / шиномонтаж / парковку»
• «Повтори останній маршрут»
• «Відкрий навігатор»
""".trimIndent() + "\n\n" + technicalCommandHelp(ukrainian = true))

    private fun technicalCommandHelp(ukrainian: Boolean): String {
        val categoryOrder = listOf(
            "climate", "seats", "windows", "sunroof", "sunshade",
            "locks", "lights", "trunk", "fridge", "other",
        )
        val categoryLabelsRu = mapOf(
            "climate" to "КЛИМАТ — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "seats" to "СИДЕНЬЯ — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "windows" to "ОКНА — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "sunroof" to "ЛЮК — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "sunshade" to "ШТОРКА — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "locks" to "ЗАМКИ — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "lights" to "ОСВЕЩЕНИЕ — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "trunk" to "БАГАЖНИКИ — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "fridge" to "ХОЛОДИЛЬНИК — ТЕХНИЧЕСКИЕ ACTION_NAME",
            "other" to "ДРУГОЕ — ТЕХНИЧЕСКИЕ ACTION_NAME",
        )
        val categoryLabelsUk = mapOf(
            "climate" to "КЛІМАТ — ТЕХНІЧНІ ACTION_NAME",
            "seats" to "СИДІННЯ — ТЕХНІЧНІ ACTION_NAME",
            "windows" to "ВІКНА — ТЕХНІЧНІ ACTION_NAME",
            "sunroof" to "ЛЮК — ТЕХНІЧНІ ACTION_NAME",
            "sunshade" to "ШТОРКА — ТЕХНІЧНІ ACTION_NAME",
            "locks" to "ЗАМКИ — ТЕХНІЧНІ ACTION_NAME",
            "lights" to "ОСВІТЛЕННЯ — ТЕХНІЧНІ ACTION_NAME",
            "trunk" to "БАГАЖНИКИ — ТЕХНІЧНІ ACTION_NAME",
            "fridge" to "ХОЛОДИЛЬНИК — ТЕХНІЧНІ ACTION_NAME",
            "other" to "ІНШЕ — ТЕХНІЧНІ ACTION_NAME",
        )
        val groups = BydWriteAllowlist.entries.groupBy { it.category }
        return buildString {
            appendLine(if (ukrainian) "ПОВНИЙ СПИСОК ТЕХНІЧНИХ КОМАНД BYDMATE" else "ПОЛНЫЙ СПИСОК ТЕХНИЧЕСКИХ КОМАНД BYDMATE")
            appendLine(
                if (ukrainian) {
                    "Формат: BYDMate <action_name> [значення]. Значення вказане в дужках; позначка ⚠ означає неперевірений fallback."
                } else {
                    "Формат: BYDMate <action_name> [значение]. Значение указано в скобках; отметка ⚠ означает непроверенный fallback."
                }
            )
            categoryOrder.forEach { category ->
                val entries = groups[category].orEmpty().sortedBy { it.actionName }
                if (entries.isEmpty()) return@forEach
                appendLine()
                appendLine((if (ukrainian) categoryLabelsUk else categoryLabelsRu).getValue(category))
                entries.forEach { entry ->
                    val valueHint = if (entry.fixedValue == null) " <${entry.valueMin}–${entry.valueMax}>" else ""
                    val warning = if (entry.validated) "" else if (ukrainian) " ⚠ не перевірено" else " ⚠ не проверено"
                    appendLine("• BYDMate ${entry.actionName}$valueHint$warning")
                }
            }
            appendLine()
            append(
                if (ukrainian) {
                    "Технічні команди використовуй лише коли розумієш призначення action_name та допустимий діапазон."
                } else {
                    "Технические команды используй только когда понимаешь назначение action_name и допустимый диапазон."
                }
            )
        }
    }

    private fun restoreSpeechOutput(runTest: Boolean, showToast: Boolean) {
        binding.buttonTestSpeech.isEnabled = false
        ioExecutor.execute {
            val boot = AasRuntime.bootstrap.ensureRunning(enableAccessibility = false)
            val engineEnabled = boot.connected && AasRuntime.helper.ensureTtsEngineEnabled()
            if (destroyed) return@execute
            runOnUiThread {
                binding.buttonTestSpeech.isEnabled = true
                AasRuntime.voice.reinitializeTts()
                if (runTest) {
                    mainHandler.postDelayed({ AasRuntime.voice.testSpeech() }, 800L)
                }
                if (showToast) {
                    val message = if (engineEnabled) R.string.tts_engine_restored else R.string.tts_engine_fallback
                    Toast.makeText(this, getString(message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun localized(text: String): String =
        if (prefs.languageTag.startsWith("uk", ignoreCase = true)) UkrainianTranslator.translate(text) else text

    private fun setVoiceResponsesSwitch(value: Boolean) {
        suppressVoiceResponsesCallback = true
        binding.switchVoiceResponses.isChecked = value
        suppressVoiceResponsesCallback = false
    }

    private fun setVehicleWriteSwitch(value: Boolean) {
        suppressWriteSwitchCallback = true
        binding.switchVehicleWrites.isChecked = value
        suppressWriteSwitchCallback = false
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AasAccessibilityService::class.java).flattenToString()
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
