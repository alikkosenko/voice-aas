package com.aas.app.ai

import com.aas.app.AppPrefs
import com.aas.app.commands.VoiceCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Converts free-form RU/UK speech into a strictly allowlisted ordered command plan.
 * The model never receives access to ADB, shell, FIDs or arbitrary action names.
 */
class OpenAiCommandPlanner(private val prefs: AppPrefs) {

    data class PlanResult(
        val commands: List<VoiceCommand>,
        val technicalMessage: String,
        val usedModel: String,
    )

    data class ConnectionTestResult(
        val success: Boolean,
        val message: String,
    )

    fun isConfigured(): Boolean = prefs.aiCommandsEnabled && prefs.hasOpenAiApiKey()

    fun plan(transcript: String): PlanResult {
        val apiKey = prefs.getOpenAiApiKey()
            ?: throw OpenAiPlannerException("OpenAI API key is not configured")
        val model = prefs.openAiModel.ifBlank { DEFAULT_MODEL }
        val request = createRequest(model, transcript, testOnly = false)
        val response = postResponses(apiKey, request)
        val outputText = extractOutputText(response)
        val plan = JSONObject(outputText)
        val understood = plan.optBoolean("understood", false)
        val note = plan.optString("note", "")
        val commandsJson = plan.optJSONArray("commands") ?: JSONArray()
        val commands = buildList {
            if (understood) {
                for (index in 0 until commandsJson.length().coerceAtMost(MAX_COMMANDS)) {
                    val item = commandsJson.optJSONObject(index) ?: continue
                    mapCommand(item)?.let(::add)
                }
            }
        }
        return PlanResult(
            commands = commands,
            technicalMessage = "AI model=$model understood=$understood commands=${commands.size} note=$note",
            usedModel = model,
        )
    }

    fun testConnection(): ConnectionTestResult {
        return try {
            val apiKey = prefs.getOpenAiApiKey()
                ?: return ConnectionTestResult(false, "API key не сохранён")
            val model = prefs.openAiModel.ifBlank { DEFAULT_MODEL }
            val response = postResponses(
                apiKey,
                createRequest(model, "Проверка подключения AAS. Не выполняй команды.", testOnly = true)
            )
            extractOutputText(response)
            ConnectionTestResult(true, "OpenAI API доступен, модель: $model")
        } catch (error: Exception) {
            ConnectionTestResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun createRequest(model: String, transcript: String, testOnly: Boolean): JSONObject {
        val request = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("max_output_tokens", 1000)
            .put(
                "input",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                if (testOnly) {
                                    "TEST_ONLY=true. Return understood=false and an empty commands array. Text: $transcript"
                                } else {
                                    "Language=${prefs.languageTag}. Driver is the speaker. User speech: $transcript"
                                }
                            )
                    )
            )
            .put(
                "text",
                JSONObject().put(
                    "format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put("name", "aas_command_plan")
                        .put("strict", true)
                        .put("schema", commandPlanSchema())
                )
            )
        return request
    }

    private fun commandPlanSchema(): JSONObject {
        val actionEnum = JSONArray(ALLOWED_ACTIONS)
        val targetEnum = JSONArray(ALLOWED_TARGETS)
        val commandSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("action", JSONObject().put("type", "string").put("enum", actionEnum))
                    .put("target", JSONObject().put("type", "string").put("enum", targetEnum))
                    .put("value", JSONObject().put("type", "integer").put("minimum", -100).put("maximum", 100))
                    .put("text", JSONObject().put("type", "string").put("maxLength", 200))
            )
            .put("required", JSONArray(listOf("action", "target", "value", "text")))

        return JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("understood", JSONObject().put("type", "boolean"))
                    .put(
                        "commands",
                        JSONObject()
                            .put("type", "array")
                            .put("maxItems", MAX_COMMANDS)
                            .put("items", commandSchema)
                    )
                    .put("note", JSONObject().put("type", "string").put("maxLength", 200))
            )
            .put("required", JSONArray(listOf("understood", "commands", "note")))
    }

    private fun postResponses(apiKey: String, body: JSONObject): JSONObject {
        val connection = (URL(RESPONSES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            val text = readText(if (code in 200..299) connection.inputStream else connection.errorStream)
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { "HTTP $code" }
                throw OpenAiPlannerException("OpenAI: $message")
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractOutputText(response: JSONObject): String {
        response.optString("output_text").trim().takeIf { it.isNotEmpty() }?.let { return it }

        val output = response.optJSONArray("output") ?: throw OpenAiPlannerException("OpenAI response has no output")
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "refusal") {
                    throw OpenAiPlannerException(part.optString("refusal", "Model refused the request"))
                }
                val text = part.optString("text").trim()
                if (text.isNotEmpty()) return text
            }
        }
        throw OpenAiPlannerException("OpenAI returned an empty plan")
    }

    private fun readText(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
    }

    private fun mapCommand(item: JSONObject): VoiceCommand? {
        val action = item.optString("action")
        val target = item.optString("target", "none")
        val value = item.optInt("value", 0)
        val text = item.optString("text").trim()

        return when (action) {
            "climate_on" -> VoiceCommand.ClimateOn
            "climate_off" -> VoiceCommand.ClimateOff
            "set_temperature" -> VoiceCommand.SetTemperature(value.coerceIn(16, 30))
            "set_fan_level" -> VoiceCommand.SetFanLevel(value.coerceIn(1, 7))
            "climate_auto_on" -> VoiceCommand.ClimateAutoOn
            "climate_auto_off" -> VoiceCommand.ClimateAutoOff
            "recirculation_inner" -> VoiceCommand.RecirculationInner
            "recirculation_outer" -> VoiceCommand.RecirculationOuter
            "rear_defrost_on" -> VoiceCommand.RearDefrostOn
            "rear_defrost_off" -> VoiceCommand.RearDefrostOff

            "sunroof_open" -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.OPEN)
            "sunroof_close" -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.CLOSE)
            "sunroof_tilt" -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.TILT)
            "sunroof_stop" -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.STOP)
            "sunroof_vent" -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.VENT)
            "sunroof_comfort" -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.COMFORT)
            "sunshade_open" -> VoiceCommand.SetSunshade(true)
            "sunshade_close" -> VoiceCommand.SetSunshade(false)
            "doors_lock" -> VoiceCommand.SetDoorLocks(true)
            "doors_unlock" -> VoiceCommand.SetDoorLocks(false)
            "interior_light_on" -> VoiceCommand.SetInteriorLight(true)
            "interior_light_off" -> VoiceCommand.SetInteriorLight(false)
            "ambient_light_on" -> VoiceCommand.SetAmbientLight(true)
            "ambient_light_off" -> VoiceCommand.SetAmbientLight(false)
            "drl_on" -> VoiceCommand.SetDaytimeRunningLights(true)
            "drl_off" -> VoiceCommand.SetDaytimeRunningLights(false)
            "front_trunk_open" -> VoiceCommand.SetFrontTrunk(true)
            "front_trunk_close" -> VoiceCommand.SetFrontTrunk(false)
            "rear_trunk_open" -> VoiceCommand.SetRearTrunk(true)
            "rear_trunk_close" -> VoiceCommand.SetRearTrunk(false)

            "seat_heating" -> VoiceCommand.SetSeatHeating(mapSeat(target), value.coerceIn(0, 3))
            "seat_ventilation" -> VoiceCommand.SetSeatVentilation(mapSeat(target), value.coerceIn(0, 3))
            "steering_heating" -> VoiceCommand.SetSteeringWheelHeating(value.coerceIn(0, 3))
            "fridge_on" -> VoiceCommand.SetFridge(true)
            "fridge_off" -> VoiceCommand.SetFridge(false)
            "fridge_temperature" -> VoiceCommand.SetFridge(
                true,
                when {
                    value <= 15 -> value.coerceIn(-6, 15)
                    else -> value.coerceIn(35, 50)
                }
            )
            "window_open" -> VoiceCommand.SetWindow(mapWindow(target), true)
            "window_close" -> VoiceCommand.SetWindow(mapWindow(target), false)
            "window_position" -> VoiceCommand.SetWindowPosition(mapWindow(target), value.coerceIn(0, 100))

            "query_soc" -> VoiceCommand.QuerySoc
            "query_range" -> VoiceCommand.QueryRange
            "query_tires" -> VoiceCommand.QueryTires
            "set_volume" -> VoiceCommand.SetVolume(value.coerceIn(0, 100), percentage = false)
            "set_volume_percent" -> VoiceCommand.SetVolume(value.coerceIn(0, 100), percentage = true)
            "adjust_volume" -> VoiceCommand.AdjustVolume(value.coerceIn(-20, 20))
            "mute" -> VoiceCommand.Mute
            "unmute" -> VoiceCommand.Unmute
            "play_pause" -> VoiceCommand.PlayPause
            "next_track" -> VoiceCommand.NextTrack
            "previous_track" -> VoiceCommand.PreviousTrack
            "navigate_to" -> text.takeIf { it.isNotEmpty() }?.let { VoiceCommand.NavigateTo(it) }
            "repeat_navigation" -> VoiceCommand.RepeatNavigation
            "open_navigator" -> VoiceCommand.OpenNavigator
            "open_music" -> VoiceCommand.OpenMusic
            "open_youtube" -> VoiceCommand.OpenYoutube
            "search_youtube" -> text.takeIf { it.isNotEmpty() }?.let { VoiceCommand.SearchYoutube(it) }
            "open_radio" -> VoiceCommand.OpenRadio
            "home" -> VoiceCommand.GoHome
            "bluetooth_on" -> VoiceCommand.SetBluetooth(true)
            "bluetooth_off" -> VoiceCommand.SetBluetooth(false)
            "wifi_on" -> VoiceCommand.SetWifi(true)
            "wifi_off" -> VoiceCommand.SetWifi(false)
            else -> null
        }
    }

    private fun mapSeat(target: String): VoiceCommand.Seat = when (target) {
        "passenger" -> VoiceCommand.Seat.PASSENGER
        "all" -> VoiceCommand.Seat.ALL
        else -> VoiceCommand.Seat.DRIVER
    }

    private fun mapWindow(target: String): VoiceCommand.Window = when (target) {
        "passenger" -> VoiceCommand.Window.PASSENGER
        "rear_left" -> VoiceCommand.Window.REAR_LEFT
        "rear_right" -> VoiceCommand.Window.REAR_RIGHT
        "front" -> VoiceCommand.Window.FRONT
        "rear" -> VoiceCommand.Window.REAR
        "all" -> VoiceCommand.Window.ALL
        else -> VoiceCommand.Window.DRIVER
    }

    class OpenAiPlannerException(message: String) : Exception(message)

    companion object {
        const val DEFAULT_MODEL = "gpt-5.6-luna"
        private const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 35_000
        private const val MAX_COMMANDS = 12

        private val ALLOWED_TARGETS = listOf(
            "none", "driver", "passenger", "all", "front", "rear", "rear_left", "rear_right"
        )

        private val ALLOWED_ACTIONS = listOf(
            "climate_on", "climate_off", "set_temperature", "set_fan_level",
            "climate_auto_on", "climate_auto_off", "recirculation_inner", "recirculation_outer",
            "rear_defrost_on", "rear_defrost_off",
            "sunroof_open", "sunroof_close", "sunroof_tilt", "sunroof_stop", "sunroof_vent", "sunroof_comfort",
            "sunshade_open", "sunshade_close", "doors_lock", "doors_unlock",
            "interior_light_on", "interior_light_off", "ambient_light_on", "ambient_light_off", "drl_on", "drl_off",
            "front_trunk_open", "front_trunk_close", "rear_trunk_open", "rear_trunk_close",
            "seat_heating", "seat_ventilation", "steering_heating", "fridge_on", "fridge_off", "fridge_temperature",
            "window_open", "window_close", "window_position",
            "query_soc", "query_range", "query_tires",
            "set_volume", "set_volume_percent", "adjust_volume", "mute", "unmute",
            "play_pause", "next_track", "previous_track",
            "navigate_to", "repeat_navigation", "open_navigator", "open_music", "open_youtube", "search_youtube",
            "open_radio", "home", "bluetooth_on", "bluetooth_off", "wifi_on", "wifi_off"
        )

        private val SYSTEM_PROMPT = """
You are the command planner for AAS, a BYD in-car assistant. Convert Russian or Ukrainian speech into an ordered list of allowed actions.

Hard rules:
1. Output only the JSON object required by the schema. Never output prose outside JSON.
2. Use only actions from the schema. Never invent shell, ADB, FID, app-package, URL, or technical actions.
3. Preserve the user's requested order. Split compound speech into up to 12 commands.
4. Do not perform dangerous or unrelated actions. Do not open windows, trunks, sunroof, unlock doors, or change driving-related systems unless the user clearly asked for that exact physical action.
5. target must be none unless an action needs a seat/window target. For an unspecified seat, use driver. For an unspecified window, use driver.
6. value is 0 when unused. text is empty when unused.
7. Seat heat/vent levels are 0=off and 1..3. Steering heat is 0..3. Fan is 1..7. Temperature is 16..30. Window position is 0..100.
8. AAS does not speak responses. Your job is only to plan executable commands.
9. If the phrase is not about supported car/system actions, set understood=false and commands=[].

Natural intent examples:
- "мне жарко" / "мені спекотно": climate_on, set_temperature=20, set_fan_level=4. Do not open windows and do not enable seat ventilation unless the user mentions the seat.
- "мне холодно" / "мені холодно": climate_on, set_temperature=24, set_fan_level=2. Do not enable seat heating unless the user mentions the seat.
- "душно": climate_on, recirculation_outer, set_fan_level=3.
- "сделай потише": adjust_volume=-2. "сделай погромче": adjust_volume=2.
- "подготовь машину, мне жарко": climate_on, set_temperature=20, set_fan_level=4.
- "выключи климат, закрой окна и включи музыку": climate_off, window_close target=all, open_music.
- "найди на ютубе обзор BYD Seal и поставь громкость 40 процентов": search_youtube text="обзор BYD Seal", set_volume_percent value=40.

For TEST_ONLY=true always return understood=false, commands=[], note="connection test".
""".trimIndent()
    }
}
