package com.aas.app.vehicle

import android.content.Context
import android.os.Build
import com.aas.app.AppPrefs
import com.aas.app.adb.AdbOnDeviceClient
import com.aas.app.helper.BootstrapResult
import com.aas.app.helper.HelperBootstrap
import com.aas.app.helper.HelperClient
import com.aas.app.commands.ExecutionResult
import com.aas.app.commands.VoiceCommand
import java.util.Locale

/**
 * Clean-room BYD autoservice adapter.
 *
 * AAS itself remains an ordinary application. A local ADB connection starts a
 * narrowly scoped app_process helper under Android's shell UID; that helper
 * performs only the verified autoservice transactions used below.
 */
class BydVehicleAdapter(
    context: Context,
    private val prefs: AppPrefs,
    private val bootstrap: HelperBootstrap,
    private val helper: HelperClient,
    private val adb: AdbOnDeviceClient,
) : VehicleAdapter {
    private val appContext = context.applicationContext
    private val seatChannelPrefs = appContext.getSharedPreferences("seat_channel", Context.MODE_PRIVATE)

    override fun diagnosticStatus(): String = when {
        helper.isAlive() -> "ADB helper и autoservice доступны"
        adb.isConnected() -> "ADB подключён, helper не запущен"
        else -> "ADB не подключён"
    }

    fun connect(): BootstrapResult = bootstrap.ensureRunning(enableAccessibility = true)

    fun readVin(): String? = if (helper.isAlive()) helper.readVin() else null

    fun readVinEnsuringConnection(): String? {
        val connection = bootstrap.ensureRunning(enableAccessibility = false)
        if (!connection.connected) return null
        return helper.readVin()
    }

    override fun execute(command: VoiceCommand.Vehicle): ExecutionResult {
        val connection = bootstrap.ensureRunning(enableAccessibility = false)
        if (!connection.connected) {
            return failure(
                spoken = "Нет подключения к сервису автомобиля",
                technical = connection.message
            )
        }

        return when (command) {
            VoiceCommand.QuerySoc -> querySoc()
            VoiceCommand.QueryTires -> queryTires()
            VoiceCommand.QueryRange -> failure(
                "Запас хода пока не поддерживается",
                "Для запаса хода в проверенной версии не найден подтверждённый autoservice-параметр"
            )

            VoiceCommand.ClimateOn -> withWritesEnabled {
                writeSingle(
                    BydParameterCatalog.climatePower,
                    value = 1,
                    spoken = "Климат включён",
                    label = "climate on"
                )
            }

            VoiceCommand.ClimateOff -> withWritesEnabled {
                writeSingle(
                    BydParameterCatalog.climatePower,
                    value = 0,
                    spoken = "Климат выключен",
                    label = "climate off"
                )
            }

            is VoiceCommand.SetTemperature -> withWritesEnabled {
                val temperature = command.celsius.coerceIn(16, 30)
                writeSingle(
                    BydParameterCatalog.climateTemperature,
                    value = temperature,
                    spoken = "Температура установлена на $temperature градусов",
                    label = "climate temperature"
                )
            }

            is VoiceCommand.SetFanLevel -> withWritesEnabled {
                val level = command.level.coerceIn(1, 7)
                writeSingle(
                    BydParameterCatalog.climateFanLevel,
                    value = level,
                    spoken = "Вентилятор установлен на уровень $level",
                    label = "climate fan level"
                )
            }

            VoiceCommand.ClimateAutoOn -> withWritesEnabled {
                writeSingle(BydParameterCatalog.climateAuto, 0, "Автоматический климат включён", "climate auto on")
            }
            VoiceCommand.ClimateAutoOff -> withWritesEnabled {
                writeSingle(BydParameterCatalog.climateAuto, 1, "Автоматический климат выключен", "climate auto off")
            }
            VoiceCommand.RecirculationInner -> withWritesEnabled {
                writeSingle(BydParameterCatalog.climateRecirculation, 1, "Включена рециркуляция воздуха", "recirculation inner")
            }
            VoiceCommand.RecirculationOuter -> withWritesEnabled {
                writeSingle(BydParameterCatalog.climateRecirculation, 0, "Включён забор воздуха с улицы", "recirculation outer")
            }
            VoiceCommand.RearDefrostOn -> withWritesEnabled {
                writeSingle(BydParameterCatalog.rearDefrost, 1, "Обогрев заднего стекла включён", "rear defrost on")
            }
            VoiceCommand.RearDefrostOff -> withWritesEnabled {
                writeSingle(BydParameterCatalog.rearDefrost, 0, "Обогрев заднего стекла выключен", "rear defrost off")
            }
            is VoiceCommand.SetSunroof -> withWritesEnabled { setSunroof(command.action) }
            is VoiceCommand.SetSunshade -> withWritesEnabled { setStationarySingle(
                BydParameterCatalog.sunshadeCommand,
                if (command.open) 1 else 2,
                if (command.open) "Шторка открыта" else "Шторка закрыта",
                "sunshade"
            ) }
            is VoiceCommand.SetDoorLocks -> withWritesEnabled {
                writeSingle(BydParameterCatalog.doorLocks, if (command.locked) 2 else 1,
                    if (command.locked) "Двери заблокированы" else "Двери разблокированы", "door locks")
            }
            is VoiceCommand.SetInteriorLight -> withWritesEnabled {
                writeSingle(BydParameterCatalog.interiorLight, if (command.enabled) 2 else 1,
                    if (command.enabled) "Свет в салоне включён" else "Свет в салоне выключен", "interior light")
            }
            is VoiceCommand.SetAmbientLight -> withWritesEnabled {
                writeSingle(BydParameterCatalog.ambientLight, if (command.enabled) 5 else 1,
                    if (command.enabled) "Подсветка салона включена" else "Подсветка салона выключена", "ambient light")
            }
            is VoiceCommand.SetDaytimeRunningLights -> withWritesEnabled {
                writeSingle(BydParameterCatalog.daytimeRunningLights, if (command.enabled) 1 else 2,
                    if (command.enabled) "Дневные огни включены" else "Дневные огни выключены", "daytime running lights")
            }
            is VoiceCommand.SetFrontTrunk -> withWritesEnabled { setStationarySingle(
                BydParameterCatalog.frontTrunk,
                if (command.open) 1 else 3,
                if (command.open) "Передний багажник открыт" else "Передний багажник закрыт",
                "front trunk"
            ) }
            is VoiceCommand.SetRearTrunk -> withWritesEnabled { setStationarySingle(
                BydParameterCatalog.rearTrunk,
                if (command.open) 1 else 3,
                if (command.open) "Багажник открыт" else "Багажник закрыт",
                "rear trunk"
            ) }

            is VoiceCommand.SetSeatHeating -> withWritesEnabled {
                setSeatLevel(command.seat, command.level, ventilation = false)
            }

            is VoiceCommand.SetSeatVentilation -> withWritesEnabled {
                setSeatLevel(command.seat, command.level, ventilation = true)
            }
            is VoiceCommand.SetSteeringWheelHeating -> withWritesEnabled {
                val level = command.level.coerceIn(0, 3)
                writeSingle(BydParameterCatalog.steeringWheelHeat, if (level == 0) 1 else level + 1,
                    if (level == 0) "Обогрев руля выключен" else "Обогрев руля включён, уровень $level",
                    "steering wheel heating")
            }
            is VoiceCommand.SetFridge -> withWritesEnabled {
                setFridge(command.enabled, command.celsius)
            }

            is VoiceCommand.SetWindow -> withWritesEnabled {
                setWindow(command.window, command.open)
            }
            is VoiceCommand.SetWindowPosition -> withWritesEnabled {
                setWindowPosition(command.window, command.percent)
            }
            is VoiceCommand.ExecuteAllowlistedAction -> withWritesEnabled {
                executeAllowlistedAction(command.actionName, command.value)
            }
        }
    }

    private fun setSunroof(action: VoiceCommand.SunroofAction): ExecutionResult {
        val value = when (action) {
            VoiceCommand.SunroofAction.OPEN -> 1
            VoiceCommand.SunroofAction.CLOSE -> 2
            VoiceCommand.SunroofAction.TILT -> 3
            VoiceCommand.SunroofAction.STOP -> 4
            VoiceCommand.SunroofAction.VENT -> 5
            VoiceCommand.SunroofAction.COMFORT -> 6
        }
        val spoken = when (action) {
            VoiceCommand.SunroofAction.OPEN -> "Люк открыт"
            VoiceCommand.SunroofAction.CLOSE -> "Люк закрыт"
            VoiceCommand.SunroofAction.TILT -> "Люк приподнят"
            VoiceCommand.SunroofAction.STOP -> "Движение люка остановлено"
            VoiceCommand.SunroofAction.VENT -> "Люк установлен в положение проветривания"
            VoiceCommand.SunroofAction.COMFORT -> "Люк установлен в комфортное положение"
        }
        return setStationarySingle(BydParameterCatalog.sunroofCommand, value, spoken, "sunroof")
    }

    private fun setStationarySingle(
        parameter: BydParameterCatalog.Parameter,
        value: Int,
        spoken: String,
        label: String
    ): ExecutionResult {
        val stationary = requireStationaryForWindow()
        if (stationary != null) return stationary
        return writeSingle(parameter, value, spoken, label)
    }

    private fun querySoc(): ExecutionResult {
        val response = readFloat(BydParameterCatalog.stateOfCharge)
        if (response.error != null) {
            return failure("Не удалось прочитать заряд", response.error)
        }
        val value = response.value
        if (!value.isFinite() || value !in 0f..100f) {
            return failure(
                "Получено некорректное значение заряда",
                "SOC raw=${response.raw}, decoded=$value"
            )
        }
        val rounded = value.toInt().coerceIn(0, 100)
        return success("Заряд батареи $rounded процентов", "SOC=$value%, raw=${response.raw}")
    }

    private fun queryTires(): ExecutionResult {
        val readings = listOf(
            "переднее левое" to readInt(BydParameterCatalog.tireFrontLeft),
            "переднее правое" to readInt(BydParameterCatalog.tireFrontRight),
            "заднее левое" to readInt(BydParameterCatalog.tireRearLeft),
            "заднее правое" to readInt(BydParameterCatalog.tireRearRight)
        )
        val failed = readings.firstOrNull { it.second.error != null }
        if (failed != null) {
            return failure(
                "Не удалось прочитать давление в шинах",
                "${failed.first}: ${failed.second.error}"
            )
        }

        val values = readings.associate { it.first to it.second.value }
        if (values.values.any { it !in 50..600 }) {
            return failure(
                "Данные давления выглядят некорректно",
                values.entries.joinToString { "${it.key}=${it.value}" }
            )
        }

        val spoken = buildString {
            append("Давление: передние ")
            append(values.getValue("переднее левое"))
            append(" и ")
            append(values.getValue("переднее правое"))
            append(", задние ")
            append(values.getValue("заднее левое"))
            append(" и ")
            append(values.getValue("заднее правое"))
            append(" килопаскалей")
        }
        return success(spoken, values.entries.joinToString { "${it.key}=${it.value} kPa" })
    }

    private fun executeAllowlistedAction(actionName: String, requestedValue: Int?): ExecutionResult {
        val entry = BydWriteAllowlist.find(actionName)
            ?: return failure(
                "Неизвестная команда BYDMate",
                "Action '$actionName' is absent from the imported WriteAllowlist"
            )

        val value = entry.fixedValue ?: requestedValue ?: return failure(
            "Для команды ${entry.actionName} нужно указать значение от ${entry.valueMin} до ${entry.valueMax}",
            "Missing value for variable allowlisted action=${entry.actionName}"
        )
        if (!entry.accepts(value)) {
            return failure(
                "Значение вне допустимого диапазона",
                "action=${entry.actionName} value=$value allowed=${entry.valueMin}..${entry.valueMax}"
            )
        }

        // Keep movement-related actions behind the same stationary proof used by
        // the normal window/sunroof/trunk commands.
        if (entry.category in setOf("windows", "sunroof", "sunshade", "trunk")) {
            val stationary = requireStationaryForWindow()
            if (stationary != null) return stationary
        }

        return writeSingle(
            BydParameterCatalog.Parameter(entry.device, entry.fid),
            value,
            "Команда ${entry.actionName} выполнена",
            "allowlist action=${entry.actionName} source=${entry.source} validated=${entry.validated}"
        )
    }

    private fun setWindowPosition(window: VoiceCommand.Window, requestedPercent: Int): ExecutionResult {
        val stationary = requireStationaryForWindow()
        if (stationary != null) return stationary
        val percent = requestedPercent.coerceIn(0, 100)
        val parameters = when (window) {
            VoiceCommand.Window.DRIVER -> listOf(BydParameterCatalog.driverWindow)
            VoiceCommand.Window.PASSENGER -> listOf(BydParameterCatalog.passengerWindow)
            VoiceCommand.Window.REAR_LEFT -> listOf(BydParameterCatalog.rearLeftWindow)
            VoiceCommand.Window.REAR_RIGHT -> listOf(BydParameterCatalog.rearRightWindow)
            VoiceCommand.Window.FRONT -> listOf(BydParameterCatalog.driverWindow, BydParameterCatalog.passengerWindow)
            VoiceCommand.Window.REAR -> listOf(BydParameterCatalog.rearLeftWindow, BydParameterCatalog.rearRightWindow)
            VoiceCommand.Window.ALL -> listOf(
                BydParameterCatalog.driverWindow,
                BydParameterCatalog.passengerWindow,
                BydParameterCatalog.rearLeftWindow,
                BydParameterCatalog.rearRightWindow
            )
        }
        val results = parameters.map { it to write(it, percent) }
        val failed = results.firstOrNull { !it.second.writeAccepted }
        if (failed != null) {
            return failure(
                "Не удалось установить положение окна",
                "dev=${failed.first.device} fid=${failed.first.fid} value=$percent ${describe(failed.second)}"
            )
        }
        return success(
            "Положение окна установлено на $percent процентов",
            results.joinToString("; ") { (parameter, result) ->
                "dev=${parameter.device} fid=${parameter.fid} value=$percent ${describe(result)}"
            }
        )
    }

    private fun setWindow(window: VoiceCommand.Window, open: Boolean): ExecutionResult {
        val stationary = requireStationaryForWindow()
        if (stationary != null) return stationary

        val targets = when (window) {
            VoiceCommand.Window.DRIVER -> listOf(
                WindowTarget(BydParameterCatalog.driverWindow, BydParameterCatalog.driverWindowFallback)
            )
            VoiceCommand.Window.PASSENGER -> listOf(
                WindowTarget(BydParameterCatalog.passengerWindow, BydParameterCatalog.passengerWindowFallback)
            )
            VoiceCommand.Window.REAR_LEFT -> listOf(
                WindowTarget(BydParameterCatalog.rearLeftWindow, BydParameterCatalog.rearLeftWindowFallback)
            )
            VoiceCommand.Window.REAR_RIGHT -> listOf(
                WindowTarget(BydParameterCatalog.rearRightWindow, BydParameterCatalog.rearRightWindowFallback)
            )
            VoiceCommand.Window.FRONT -> listOf(
                WindowTarget(BydParameterCatalog.driverWindow, BydParameterCatalog.driverWindowFallback),
                WindowTarget(BydParameterCatalog.passengerWindow, BydParameterCatalog.passengerWindowFallback)
            )
            VoiceCommand.Window.REAR -> listOf(
                WindowTarget(BydParameterCatalog.rearLeftWindow, BydParameterCatalog.rearLeftWindowFallback),
                WindowTarget(BydParameterCatalog.rearRightWindow, BydParameterCatalog.rearRightWindowFallback)
            )
            VoiceCommand.Window.ALL -> listOf(
                WindowTarget(BydParameterCatalog.driverWindow, BydParameterCatalog.driverWindowFallback),
                WindowTarget(BydParameterCatalog.passengerWindow, BydParameterCatalog.passengerWindowFallback),
                WindowTarget(BydParameterCatalog.rearLeftWindow, BydParameterCatalog.rearLeftWindowFallback),
                WindowTarget(BydParameterCatalog.rearRightWindow, BydParameterCatalog.rearRightWindowFallback)
            )
        }

        val results = targets.map { writeWindowTarget(it, open) }
        val failed = results.firstOrNull { !it.accepted }
        if (failed != null) {
            return failure(
                "Не удалось изменить положение окна",
                failed.technical
            )
        }

        val targetName = when (window) {
            VoiceCommand.Window.DRIVER -> "Водительское окно"
            VoiceCommand.Window.PASSENGER -> "Пассажирское окно"
            VoiceCommand.Window.REAR_LEFT -> "Заднее левое окно"
            VoiceCommand.Window.REAR_RIGHT -> "Заднее правое окно"
            VoiceCommand.Window.FRONT -> "Передние окна"
            VoiceCommand.Window.REAR -> "Задние окна"
            VoiceCommand.Window.ALL -> "Все окна"
        }
        return success(
            "$targetName ${if (open) "открыто" else "закрыто"}",
            results.joinToString("; ") { it.technical }
        )
    }

    private fun writeWindowTarget(target: WindowTarget, open: Boolean): WindowWriteResult {
        val positionValue = if (open) 100 else 0
        val primary = write(target.position, positionValue)
        if (primary.writeAccepted) {
            return WindowWriteResult(
                true,
                "window position dev=${target.position.device} fid=${target.position.fid} " +
                    "value=$positionValue ${describe(primary)}"
            )
        }

        val commandValue = if (open) 1 else 2
        val fallback = write(target.commandFallback, commandValue)
        return WindowWriteResult(
            fallback.writeAccepted,
            "window primary=${describe(primary)}; fallback dev=${target.commandFallback.device} " +
                "fid=${target.commandFallback.fid} value=$commandValue ${describe(fallback)}"
        )
    }

    /**
     * Window commands are rejected unless AAS can prove that the vehicle is
     * stationary. This is deliberately stricter than the source application.
     */
    private fun requireStationaryForWindow(): ExecutionResult? {
        val speed = readFloat(BydParameterCatalog.vehicleSpeed)
        if (speed.error != null || !speed.value.isFinite()) {
            return failure(
                "Окна не изменены: не удалось проверить скорость",
                speed.error ?: "invalid speed raw=${speed.raw} decoded=${speed.value}"
            )
        }
        if (speed.value > 0.5f) {
            return failure(
                "Команда окон доступна только на остановленной машине",
                "vehicleSpeed=${format(speed.value)}"
            )
        }
        return null
    }

    private fun setSeatLevel(
        seat: VoiceCommand.Seat,
        requestedLevel: Int,
        ventilation: Boolean
    ): ExecutionResult {
        if (seat == VoiceCommand.Seat.ALL) {
            val driver = setSeatLevel(VoiceCommand.Seat.DRIVER, requestedLevel, ventilation)
            val passenger = setSeatLevel(VoiceCommand.Seat.PASSENGER, requestedLevel, ventilation)
            return if (driver.success || passenger.success) {
                success(
                    "Команда сидений выполнена",
                    driver.technicalMessage + "; " + passenger.technicalMessage
                )
            } else {
                failure(
                    "Не удалось изменить режим всех сидений",
                    driver.technicalMessage + "; " + passenger.technicalMessage
                )
            }
        }

        val level = requestedLevel.coerceIn(0, 3)
        val target = seatParameters(seat, ventilation)
        val diagnostics = mutableListOf<String>()

        // Different BYD/DiLink generations expose one of two seat-control
        // contracts. A transport status of 1 is not sufficient to identify the
        // physically active contract, so write the same requested state to both.
        // The values are equivalent and therefore do not fight each other.
        var primaryAccepted = false
        if (level == 0) {
            val primary = write(target.switch, 2) // primary: 2 = off
            diagnostics += "primary switch(value=2)=${describe(primary)}"
            primaryAccepted = primary.error == null && primary.status >= 0
        } else {
            // Apply level first, then switch. Several DiLink climate builds only
            // commit the staged level when the enable edge follows it.
            val stage = write(target.level, level)
            diagnostics += "primary level(value=$level)=${describe(stage)}"
            val primary = write(target.switch, 1) // primary: 1 = on
            diagnostics += "primary switch(value=1)=${describe(primary)}"
            primaryAccepted = stage.error == null && primary.error == null &&
                (stage.status >= 0 || primary.status >= 0)
        }

        val fallbackValue = if (level == 0) 1 else level + 1 // 1=off, 2..4=levels 1..3
        val fallback = write(target.fallback, fallbackValue)
        diagnostics += "fallback(value=$fallbackValue)=${describe(fallback)}"
        val fallbackAccepted = fallback.error == null && fallback.status >= 0

        // Forget any winner cached by previous versions; it may have been selected
        // from a false-positive transport status rather than physical actuation.
        setSeatChannelWinner(SeatChannel.UNKNOWN)

        return if (primaryAccepted || fallbackAccepted) {
            success("Команда сиденья отправлена", diagnostics.joinToString("; "))
        } else {
            failure("Не удалось изменить режим сиденья", diagnostics.joinToString("; "))
        }
    }

    private data class SeatParameters(
        val switch: BydParameterCatalog.Parameter,
        val level: BydParameterCatalog.Parameter,
        val fallback: BydParameterCatalog.Parameter
    )

    private fun seatParameters(seat: VoiceCommand.Seat, ventilation: Boolean): SeatParameters = when (seat) {
        VoiceCommand.Seat.DRIVER -> if (ventilation) {
            SeatParameters(
                BydParameterCatalog.driverSeatVentSwitch,
                BydParameterCatalog.driverSeatVentLevel,
                BydParameterCatalog.driverSeatVentFallback
            )
        } else {
            SeatParameters(
                BydParameterCatalog.driverSeatHeatSwitch,
                BydParameterCatalog.driverSeatHeatLevel,
                BydParameterCatalog.driverSeatHeatFallback
            )
        }
        VoiceCommand.Seat.PASSENGER -> if (ventilation) {
            SeatParameters(
                BydParameterCatalog.passengerSeatVentSwitch,
                BydParameterCatalog.passengerSeatVentLevel,
                BydParameterCatalog.passengerSeatVentFallback
            )
        } else {
            SeatParameters(
                BydParameterCatalog.passengerSeatHeatSwitch,
                BydParameterCatalog.passengerSeatHeatLevel,
                BydParameterCatalog.passengerSeatHeatFallback
            )
        }
        VoiceCommand.Seat.ALL -> error("ALL is handled before parameter selection")
    }

    private fun seatOutcome(value: TransportValue): SeatWriteOutcome = when {
        value.error != null -> SeatWriteOutcome.TRANSIENT
        value.status >= 1 -> SeatWriteOutcome.REAL
        value.status == 0 -> SeatWriteOutcome.NOOP
        value.status == -10011 -> SeatWriteOutcome.PERMANENT_DENIED
        else -> SeatWriteOutcome.TRANSIENT
    }

    private fun seatChannelWinner(): SeatChannel {
        if (seatChannelPrefs.getInt(KEY_SEAT_CHANNEL_VERSION, -1) != SEAT_CHANNEL_SCHEMA_VERSION) {
            return SeatChannel.UNKNOWN
        }
        if (seatChannelPrefs.getString(KEY_SEAT_CHANNEL_FINGERPRINT, "") != currentFingerprint()) {
            return SeatChannel.UNKNOWN
        }
        return runCatching {
            SeatChannel.valueOf(
                seatChannelPrefs.getString(KEY_SEAT_CHANNEL, SeatChannel.UNKNOWN.name)
                    ?: SeatChannel.UNKNOWN.name
            )
        }.getOrDefault(SeatChannel.UNKNOWN)
    }

    private fun setSeatChannelWinner(channel: SeatChannel) {
        seatChannelPrefs.edit()
            .putInt(KEY_SEAT_CHANNEL_VERSION, SEAT_CHANNEL_SCHEMA_VERSION)
            .putString(KEY_SEAT_CHANNEL_FINGERPRINT, currentFingerprint())
            .putString(KEY_SEAT_CHANNEL, channel.name)
            .apply()
    }

    private fun currentFingerprint(): String = Build.FINGERPRINT ?: ""

    private enum class SeatChannel { UNKNOWN, PRIMARY, FALLBACK }
    private enum class SeatWriteOutcome { REAL, NOOP, PERMANENT_DENIED, TRANSIENT }

    private fun setFridge(enabled: Boolean, requestedCelsius: Int?): ExecutionResult {
        if (!enabled) {
            return writeSingle(BydParameterCatalog.fridgeMode, 3, "Холодильник выключен", "fridge off")
        }
        val celsius = requestedCelsius ?: 3
        val heating = celsius >= 35
        if (celsius in 16..34) {
            return failure(
                "Холодильник поддерживает охлаждение от минус 6 до 15 и нагрев от 35 до 50 градусов",
                "unsupported fridge temperature=$celsius"
            )
        }
        val target = if (heating) celsius.coerceIn(35, 50) else celsius.coerceIn(-6, 15)
        val modeValue = if (heating) 2 else 1
        val mode = write(BydParameterCatalog.fridgeMode, modeValue)
        if (!mode.writeAccepted) return failure("Не удалось включить холодильник", describe(mode))
        val rawTemperature = if (heating) target else target + 19
        val temp = write(BydParameterCatalog.fridgeTemperature, rawTemperature)
        if (!temp.writeAccepted) return failure("Холодильник включён, но температура не установлена", describe(temp))
        val modeName = if (heating) "нагрева" else "охлаждения"
        return success(
            "Холодильник включён в режиме $modeName, температура $target градусов",
            "fridge mode=$modeValue; temp=$target raw=$rawTemperature"
        )
    }

    private fun withWritesEnabled(block: () -> ExecutionResult): ExecutionResult {
        if (!prefs.vehicleWritesEnabled) {
            return failure(
                "Управление автомобилем выключено в настройках AAS",
                "Vehicle writes are disabled; enable vehicle commands in the app"
            )
        }
        return block()
    }

    private fun writeSingle(
        parameter: BydParameterCatalog.Parameter,
        value: Int,
        spoken: String,
        label: String
    ): ExecutionResult {
        val result = write(parameter, value)
        return if (result.writeAccepted) {
            success(
                spoken,
                "$label dev=${parameter.device} fid=${parameter.fid} value=$value; ${describe(result)}"
            )
        } else {
            failure(
                "Команда автомобилем не принята",
                "$label dev=${parameter.device} fid=${parameter.fid} value=$value; ${describe(result)}"
            )
        }
    }

    private fun readInt(parameter: BydParameterCatalog.Parameter): IntRead {
        val result = helper.read(
            BydParameterCatalog.READ_INT,
            parameter.device,
            parameter.fid
        ) ?: return IntRead(0, "helper unavailable")
        return if (result.readSucceeded) {
            IntRead(result.value, null)
        } else {
            IntRead(0, "status=${result.status}, value=${result.value}")
        }
    }

    private fun readFloat(parameter: BydParameterCatalog.Parameter): FloatRead {
        val result = helper.read(
            BydParameterCatalog.READ_FLOAT_BITS,
            parameter.device,
            parameter.fid
        ) ?: return FloatRead(Float.NaN, 0, "helper unavailable")
        return if (result.readSucceeded) {
            FloatRead(Float.fromBits(result.value), result.value, null)
        } else {
            FloatRead(Float.NaN, result.value, "status=${result.status}, value=${result.value}")
        }
    }

    private fun write(parameter: BydParameterCatalog.Parameter, value: Int): TransportValue {
        val result = helper.write(parameter.device, parameter.fid, value)
            ?: return TransportValue(-1, 0, "helper unavailable")
        return TransportValue(result.status, result.value, null)
    }

    private fun describe(value: TransportValue): String = value.error
        ?: "status=${value.status}, value=${value.value}"

    private fun success(spoken: String, technical: String): ExecutionResult =
        ExecutionResult(true, spoken, technical)

    private fun failure(spoken: String, technical: String): ExecutionResult =
        ExecutionResult(false, spoken, technical)

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    private data class TransportValue(val status: Int, val value: Int, val error: String?) {
        val readSucceeded: Boolean get() = error == null && status == 0
        val writeAccepted: Boolean get() = error == null && status >= 0
    }

    private data class IntRead(val value: Int, val error: String?)
    private data class FloatRead(val value: Float, val raw: Int, val error: String?)
    private data class WindowTarget(
        val position: BydParameterCatalog.Parameter,
        val commandFallback: BydParameterCatalog.Parameter
    )
    private data class WindowWriteResult(val accepted: Boolean, val technical: String)

    private companion object {
        const val SEAT_CHANNEL_SCHEMA_VERSION = 3
        const val KEY_SEAT_CHANNEL_VERSION = "schema_version"
        const val KEY_SEAT_CHANNEL_FINGERPRINT = "build_fingerprint"
        const val KEY_SEAT_CHANNEL = "winner"
    }
}
