package com.aas.app.commands

import android.content.Context
import com.aas.app.AppPrefs
import com.aas.app.system.AndroidActions
import com.aas.app.vehicle.VehicleAdapter

class CommandDispatcher(
    context: Context,
    private val vehicleAdapter: VehicleAdapter
) {
    private val appContext = context.applicationContext
    private val androidActions = AndroidActions(appContext)
    private val prefs = AppPrefs(appContext)

    fun execute(command: VoiceCommand): ExecutionResult = when (command) {
        is VoiceCommand.Vehicle -> vehicleAdapter.execute(command)
        is VoiceCommand.System -> executeSystem(command)
    }

    private fun executeSystem(command: VoiceCommand.System): ExecutionResult = when (command) {
        is VoiceCommand.SetVolume -> androidActions.setVolume(command.level, command.percentage)
        is VoiceCommand.AdjustVolume -> androidActions.adjustVolume(command.delta)
        VoiceCommand.VolumeUp -> androidActions.adjustVolume(1)
        VoiceCommand.VolumeDown -> androidActions.adjustVolume(-1)
        VoiceCommand.Mute -> androidActions.setMute(true)
        VoiceCommand.Unmute -> androidActions.setMute(false)
        VoiceCommand.PlayPause -> androidActions.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Воспроизведение переключено")
        VoiceCommand.NextTrack -> androidActions.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, "Следующий трек")
        VoiceCommand.PreviousTrack -> androidActions.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Предыдущий трек")
        is VoiceCommand.NavigateTo -> androidActions.openWaze(command.destination)
        VoiceCommand.RepeatNavigation -> androidActions.repeatLastWazeRoute()
        VoiceCommand.OpenNavigator -> androidActions.openSelectedApp(
            prefs.navigationPackage,
            listOf("ru.yandex.yandexnavi", "com.google.android.apps.maps"),
            "Навигатор открыт"
        )
        VoiceCommand.OpenMusic -> androidActions.openSelectedApp(
            prefs.musicPackage,
            AndroidActions.MUSIC_PACKAGES,
            "Музыка открыта"
        )
        VoiceCommand.OpenYoutube -> androidActions.openSelectedApp(
            prefs.youtubePackage,
            AndroidActions.YOUTUBE_PACKAGES,
            "YouTube открыт"
        )
        is VoiceCommand.SearchYoutube -> androidActions.searchYoutube(
            command.query,
            prefs.youtubePackage
        )
        is VoiceCommand.PlayYoutube -> androidActions.playYoutube(
            command.query,
            prefs.youtubePackage
        )
        is VoiceCommand.SearchMusic -> androidActions.searchMusic(
            command.query,
            prefs.musicPackage
        )
        is VoiceCommand.PlayMusic -> androidActions.playMusic(
            command.query,
            prefs.musicPackage
        )
        VoiceCommand.OpenRadio -> androidActions.openSelectedApp(
            prefs.radioPackage,
            listOf("com.android.fmradio", "com.miui.fmradio", "com.sec.android.app.fm", "com.google.android.apps.podcasts"),
            "Радио открыто"
        )
        VoiceCommand.GoHome -> androidActions.goHome()
        is VoiceCommand.SetBluetooth -> androidActions.setBluetooth(command.enabled)
        is VoiceCommand.SetWifi -> androidActions.setWifi(command.enabled)
        is VoiceCommand.SpeakText -> ExecutionResult(true, command.text)
    }
}
