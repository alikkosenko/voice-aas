package com.aas.app.commands

sealed interface VoiceCommand {
    sealed interface Vehicle : VoiceCommand
    sealed interface System : VoiceCommand

    data object ClimateOn : Vehicle
    data object ClimateOff : Vehicle
    data class SetTemperature(val celsius: Int) : Vehicle
    data class SetFanLevel(val level: Int) : Vehicle
    data object ClimateAutoOn : Vehicle
    data object ClimateAutoOff : Vehicle
    data object RecirculationInner : Vehicle
    data object RecirculationOuter : Vehicle
    data object RearDefrostOn : Vehicle
    data object RearDefrostOff : Vehicle

    enum class SunroofAction { OPEN, CLOSE, TILT, STOP, VENT, COMFORT }
    data class SetSunroof(val action: SunroofAction) : Vehicle
    data class SetSunshade(val open: Boolean) : Vehicle
    data class SetDoorLocks(val locked: Boolean) : Vehicle
    data class SetInteriorLight(val enabled: Boolean) : Vehicle
    data class SetAmbientLight(val enabled: Boolean) : Vehicle
    data class SetDaytimeRunningLights(val enabled: Boolean) : Vehicle
    data class SetFrontTrunk(val open: Boolean) : Vehicle
    data class SetRearTrunk(val open: Boolean) : Vehicle

    enum class Seat { DRIVER, PASSENGER, ALL }
    enum class Window { DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT, FRONT, REAR, ALL }

    data class SetSeatHeating(val seat: Seat, val level: Int) : Vehicle
    data class SetSeatVentilation(val seat: Seat, val level: Int) : Vehicle
    data class SetSteeringWheelHeating(val level: Int) : Vehicle
    data class SetFridge(val enabled: Boolean, val celsius: Int? = null) : Vehicle
    data class SetWindow(val window: Window, val open: Boolean) : Vehicle
    data class SetWindowPosition(val window: Window, val percent: Int) : Vehicle

    /** Execute one exact action from the imported BYDMate WriteAllowlist. */
    data class ExecuteAllowlistedAction(val actionName: String, val value: Int? = null) : Vehicle

    data object QuerySoc : Vehicle
    data object QueryRange : Vehicle
    data object QueryTires : Vehicle

    /** Plain numbers are native Android/BYD media-volume steps; percentage is explicit. */
    data class SetVolume(val level: Int, val percentage: Boolean = false) : System
    data class AdjustVolume(val delta: Int) : System
    data object VolumeUp : System
    data object VolumeDown : System
    data object Mute : System
    data object Unmute : System
    data object PlayPause : System
    data object NextTrack : System
    data object PreviousTrack : System
    data class NavigateTo(val destination: String) : System
    data object RepeatNavigation : System
    data object OpenNavigator : System
    data object OpenMusic : System
    data object OpenYoutube : System
    data class SearchYoutube(val query: String) : System
    data object OpenRadio : System
    data object GoHome : System
    data class SetBluetooth(val enabled: Boolean) : System
    data class SetWifi(val enabled: Boolean) : System
    data class SpeakText(val text: String) : System
}

data class ExecutionResult(
    val success: Boolean,
    val spokenMessage: String,
    val technicalMessage: String = spokenMessage
)
