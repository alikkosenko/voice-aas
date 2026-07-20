package com.aas.app.system

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.aas.app.commands.ExecutionResult
import com.aas.app.helper.HelperBinderProtocol
import com.aas.app.helper.HelperClient
import kotlin.math.roundToInt

class AndroidActions(private val context: Context) {
    private val navigationPrefs = context.getSharedPreferences("navigation_history", Context.MODE_PRIVATE)

    fun openWaze(destination: String): ExecutionResult {
        val cleanDestination = destination.trim()
        if (cleanDestination.isEmpty()) return ExecutionResult(false, "Адрес не указан")

        val uri = Uri.parse("https://waze.com/ul?q=" + Uri.encode(cleanDestination) + "&navigate=yes")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.waze")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            navigationPrefs.edit().putString("last_destination", cleanDestination).apply()
            ExecutionResult(true, "Прокладываю маршрут до $cleanDestination", "wazeQuery=$cleanDestination")
        } catch (e: Exception) {
            ExecutionResult(false, "Waze не установлен", e.toString())
        }
    }

    fun repeatLastWazeRoute(): ExecutionResult {
        val destination = navigationPrefs.getString("last_destination", null)?.trim().orEmpty()
        return if (destination.isEmpty()) {
            ExecutionResult(false, "Последний маршрут не найден")
        } else {
            openWaze(destination)
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val helper = HelperClient()

    /**
     * Sets the media volume. A plain spoken number is a native BYD/Android volume
     * step. Percentage conversion is used only when the user explicitly says
     * "процентов/відсотків".
     */
    fun setVolume(level: Int, percentage: Boolean = false): ExecutionResult {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = if (percentage) {
            ((level.coerceIn(0, 100) / 100.0) * max).roundToInt().coerceIn(0, max)
        } else {
            level.coerceIn(0, max)
        }
        val applied = applyVolumeTarget(target, max)
        val spoken = when {
            percentage -> "Громкость ${level.coerceIn(0, 100)} процентов"
            else -> "Громкость установлена на уровень $target из $max"
        }
        return ExecutionResult(
            applied.accepted,
            if (applied.accepted) spoken else "Не удалось установить громкость. Текущий уровень ${applied.actual} из $max",
            "requested=$level percentage=$percentage target=$target actual=${applied.actual} max=$max helper=${applied.helperAccepted} framework=${applied.frameworkMatched}"
        )
    }

    /**
     * Changes the active automotive volume by exactly [delta] hardware-key steps.
     * This path deliberately does not convert the request into an absolute music
     * stream value: some DiLink builds expose a stale STREAM_MUSIC index while the
     * steering-wheel/vehicle volume policy still reacts correctly to key events.
     */
    fun adjustVolume(delta: Int): ExecutionResult {
        if (delta == 0) return ExecutionResult(true, "Громкость не изменена")
        val steps = kotlin.math.abs(delta).coerceIn(1, 20)
        val operation = if (delta > 0) HelperBinderProtocol.VOLUME_UP else HelperBinderProtocol.VOLUME_DOWN
        val frameworkDirection = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        var helperAccepted = true
        repeat(steps) {
            if (!helper.controlVolume(operation)) helperAccepted = false
            SystemClock.sleep(35L)
        }
        if (!helperAccepted) {
            repeat(steps) {
                runCatching {
                    audioManager.adjustSuggestedStreamVolume(
                        frameworkDirection,
                        AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }
                SystemClock.sleep(35L)
            }
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        val direction = if (delta > 0) "громче" else "тише"
        return ExecutionResult(
            true,
            "Сделал $direction на $steps ${stepWord(steps)}",
            "delta=$delta steps=$steps helper=$helperAccepted observedStreamMusic=$actual/$max"
        )
    }

    /**
     * Absolute volume first goes through the shell helper. The helper verifies the
     * shell media command and falls back to real hardware-key normalization. The
     * framework path remains a second fallback for non-BYD Android devices.
     */
    private fun applyVolumeTarget(target: Int, max: Int): VolumeApplyResult {
        val clamped = target.coerceIn(0, max)
        val helperAccepted = helper.controlVolume(HelperBinderProtocol.VOLUME_SET, clamped)
        SystemClock.sleep(140L)
        var actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        if (helperAccepted || actual == clamped) {
            return VolumeApplyResult(true, helperAccepted, actual == clamped, actual)
        }

        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI)
        }
        SystemClock.sleep(100L)
        actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return VolumeApplyResult(actual == clamped, false, actual == clamped, actual)
    }

    private fun stepWord(value: Int): String = when {
        value % 100 in 11..14 -> "шагов"
        value % 10 == 1 -> "шаг"
        value % 10 in 2..4 -> "шага"
        else -> "шагов"
    }

    private data class VolumeApplyResult(
        val accepted: Boolean,
        val helperAccepted: Boolean,
        val frameworkMatched: Boolean,
        val actual: Int,
    )

    fun setMute(mute: Boolean): ExecutionResult {
        val operation = if (mute) HelperBinderProtocol.VOLUME_MUTE else HelperBinderProtocol.VOLUME_UNMUTE
        val helperAccepted = helper.controlVolume(operation)
        if (!helperAccepted) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI
            )
        }
        return ExecutionResult(true, if (mute) "Звук выключен" else "Звук включён", "helper=$helperAccepted")
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun setBluetooth(enabled: Boolean): ExecutionResult {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return ExecutionResult(false, "Bluetooth недоступен")
            val accepted = if (enabled) adapter.enable() else adapter.disable()
            ExecutionResult(
                accepted || adapter.isEnabled == enabled,
                if (enabled) "Bluetooth включён" else "Bluetooth выключен"
            )
        } catch (e: Exception) {
            ExecutionResult(false, "Не удалось изменить Bluetooth", e.message ?: e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    fun setWifi(enabled: Boolean): ExecutionResult = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val helperAccepted = helper.setWifiEnabled(enabled)
        val frameworkAccepted = if (!helperAccepted) {
            runCatching { wifi.setWifiEnabled(enabled) }.getOrDefault(false)
        } else false
        val currentState = runCatching { wifi.isWifiEnabled }.getOrNull()
        val success = helperAccepted || frameworkAccepted || currentState == enabled

        ExecutionResult(
            success,
            if (success) {
                if (enabled) "Wi-Fi включён" else "Wi-Fi выключен"
            } else {
                "Не удалось изменить Wi-Fi"
            },
            "helper=$helperAccepted framework=$frameworkAccepted current=$currentState requested=$enabled"
        )
    } catch (e: Exception) {
        ExecutionResult(false, "Не удалось изменить Wi-Fi", e.message ?: e.javaClass.simpleName)
    }

    fun mediaKey(keyCode: Int, spoken: String): ExecutionResult {
        val downTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        return ExecutionResult(true, spoken)
    }

    fun openFirstApp(packages: List<String>, spoken: String): ExecutionResult {
        for (packageName in packages) {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: continue
            val component = launch.component ?: continue

            try {
                // На некоторых автомобильных прошивках повторный обычный launch intent
                // не поднимает уже существующую задачу. makeRestartActivityTask()
                // пересоздаёт корневую задачу выбранного приложения и гарантированно
                // выводит её на передний план.
                val foregroundIntent = Intent.makeRestartActivityTask(component).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                }
                context.startActivity(foregroundIntent)
                return ExecutionResult(true, spoken, "Запущен пакет $packageName через ${component.flattenToShortString()}")
            } catch (_: Exception) {
                // Резервный вариант для приложений, которые не принимают restart-task intent.
                try {
                    launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    context.startActivity(launch)
                    return ExecutionResult(true, spoken, "Запущен пакет $packageName")
                } catch (_: Exception) {
                    // Пробуем следующий пакет.
                }
            }
        }
        return ExecutionResult(false, "Подходящее приложение не установлено")
    }


    fun openSelectedApp(packageName: String, fallbackPackages: List<String>, spoken: String): ExecutionResult {
        val candidates = buildList {
            if (packageName.isNotBlank()) add(packageName)
            addAll(fallbackPackages.filterNot { it == packageName })
        }
        return openFirstApp(candidates, spoken)
    }

    /**
     * Opens a search directly in the selected YouTube/ReVanced application.
     *
     * Some YouTube builds accept ACTION_SEARCH but only bring the existing home
     * activity to the foreground and silently ignore SearchManager.QUERY. The
     * dedicated YouTube search action is therefore tried first, followed by
     * explicit result-page deep links and finally the generic Android search action.
     */
    fun searchYoutube(query: String, preferredPackage: String): ExecutionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return openSelectedApp(preferredPackage, YOUTUBE_PACKAGES, "YouTube открыт")
        }

        val candidates = buildList {
            if (preferredPackage.isNotBlank()) add(preferredPackage)
            addAll(YOUTUBE_PACKAGES.filterNot { it == preferredPackage })
        }.distinct()

        val webResultsUri = Uri.Builder()
            .scheme("https")
            .authority("www.youtube.com")
            .path("results")
            .appendQueryParameter("search_query", cleanQuery)
            .build()
        val nativeResultsUri = Uri.Builder()
            .scheme("vnd.youtube")
            .authority("results")
            .appendQueryParameter("search_query", cleanQuery)
            .build()

        fun Intent.withYoutubeQuery(): Intent = apply {
            putExtra(SearchManager.QUERY, cleanQuery)
            putExtra("query", cleanQuery)
            putExtra("search_query", cleanQuery)
            putExtra(Intent.EXTRA_TEXT, cleanQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        for (packageName in candidates) {
            val intents = listOf(
                "youtube_open_search" to Intent(YOUTUBE_OPEN_SEARCH_ACTION).apply {
                    setPackage(packageName)
                }.withYoutubeQuery(),
                "https_results" to Intent(Intent.ACTION_VIEW, webResultsUri).apply {
                    setPackage(packageName)
                }.withYoutubeQuery(),
                "native_results" to Intent(Intent.ACTION_VIEW, nativeResultsUri).apply {
                    setPackage(packageName)
                }.withYoutubeQuery(),
                "android_search" to Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(packageName)
                }.withYoutubeQuery()
            )

            for ((method, intent) in intents) {
                try {
                    context.startActivity(intent)
                    return ExecutionResult(
                        true,
                        "Ищу в YouTube: $cleanQuery",
                        "youtubeSearch package=$packageName method=$method query=$cleanQuery"
                    )
                } catch (_: Exception) {
                    // Try the next intent contract or package.
                }
            }
        }

        return ExecutionResult(
            false,
            "YouTube ReVanced не найден. Выбери его в настройках AAS",
            "youtubeSearch failed query=$cleanQuery preferredPackage=$preferredPackage"
        )
    }

    fun goHome(): ExecutionResult {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ExecutionResult(true, "Главный экран")
    }

    companion object {
        private const val YOUTUBE_OPEN_SEARCH_ACTION = "com.google.android.youtube.action.open.search"

        /** Selected app is always tried first; these are automatic fallbacks. */
        val YOUTUBE_PACKAGES: List<String> = listOf(
            "com.google.android.youtube",          // Original package / root ReVanced
            "app.revanced.android.youtube",       // ReVanced custom package
            "app.rvx.android.youtube",            // ReVanced Extended custom package
            "com.google.android.youtube.revanced",
            "com.google.android.youtube.revanced.extended"
        )
    }
}
