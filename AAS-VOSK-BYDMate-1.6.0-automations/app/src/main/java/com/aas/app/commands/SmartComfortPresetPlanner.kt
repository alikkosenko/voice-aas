package com.aas.app.commands

import java.util.Locale

/**
 * Deterministic offline comfort presets for natural temperature complaints.
 * These phrases are intentionally handled before the generic command parser,
 * so common comfort requests never require an OpenAI request.
 */
class SmartComfortPresetPlanner {
    enum class Profile { HOT, VERY_HOT, COLD, VERY_COLD }

    data class Plan(
        val profile: Profile,
        val commands: List<VoiceCommand>,
        val spokenSummary: String
    )

    fun plan(raw: String): Plan? {
        val text = normalize(raw)
        if (text.isBlank() || looksLikeQuestion(text) || containsNegatedComplaint(text)) return null

        if (containsUnrelatedExplicitCommand(text)) return null

        val words = text.split(' ').filter(String::isNotBlank)
        val personalOrDirect = words.size <= 5 || PERSONAL_MARKERS.any(text::contains)
        if (!personalOrDirect) return null

        val hot = HOT_MARKERS.any(text::contains) || HOT_REQUESTS.any(text::contains)
        val cold = COLD_MARKERS.any(text::contains) || COLD_REQUESTS.any(text::contains)
        if (hot == cold) return null // neither or contradictory

        val strong = INTENSITY_MARKERS.any(text::contains) ||
            VERY_HOT_MARKERS.any(text::contains) ||
            VERY_COLD_MARKERS.any(text::contains)

        val profile = when {
            hot && strong -> Profile.VERY_HOT
            hot -> Profile.HOT
            cold && strong -> Profile.VERY_COLD
            else -> Profile.COLD
        }

        return when (profile) {
            Profile.VERY_COLD -> Plan(
                profile = profile,
                commands = listOf(
                    VoiceCommand.ClimateOn,
                    VoiceCommand.ClimateFlowOnlyOff,
                    VoiceCommand.ClimateAutoOff,
                    VoiceCommand.SetSeatVentilation(VoiceCommand.Seat.ALL, 0),
                    VoiceCommand.SetTemperature(28),
                    VoiceCommand.SetFanLevel(3),
                    VoiceCommand.SetSeatHeating(VoiceCommand.Seat.ALL, 2),
                    VoiceCommand.SetSteeringWheelHeating(1)
                ),
                spokenSummary = "Готово. Усиленный обогрев включён: климат 28 градусов, обдув 3, сиденья 2, руль 1"
            )

            Profile.VERY_HOT -> Plan(
                profile = profile,
                commands = listOf(
                    VoiceCommand.ClimateOn,
                    VoiceCommand.ClimateFlowOnlyOff,
                    VoiceCommand.FrontDefrostOff,
                    VoiceCommand.RearDefrostOff,
                    VoiceCommand.SetSeatHeating(VoiceCommand.Seat.ALL, 0),
                    VoiceCommand.SetSteeringWheelHeating(0),
                    VoiceCommand.SetTemperature(20),
                    VoiceCommand.SetSeatVentilation(VoiceCommand.Seat.ALL, 2)
                ),
                spokenSummary = "Готово. Усиленное охлаждение включено: климат 20 градусов, вентиляция сидений 2, обогревы выключены"
            )

            Profile.COLD -> Plan(
                profile = profile,
                commands = listOf(
                    VoiceCommand.ClimateOn,
                    VoiceCommand.ClimateFlowOnlyOff,
                    VoiceCommand.ClimateAutoOff,
                    VoiceCommand.SetSeatVentilation(VoiceCommand.Seat.ALL, 0),
                    VoiceCommand.SetTemperature(25),
                    VoiceCommand.SetFanLevel(2),
                    VoiceCommand.SetSeatHeating(VoiceCommand.Seat.ALL, 1),
                    VoiceCommand.SetSteeringWheelHeating(1)
                ),
                spokenSummary = "Готово. Включён мягкий обогрев: климат 25 градусов, обдув 2, сиденья 1"
            )

            Profile.HOT -> Plan(
                profile = profile,
                commands = listOf(
                    VoiceCommand.ClimateOn,
                    VoiceCommand.ClimateFlowOnlyOff,
                    VoiceCommand.ClimateAutoOff,
                    VoiceCommand.FrontDefrostOff,
                    VoiceCommand.RearDefrostOff,
                    VoiceCommand.SetSeatHeating(VoiceCommand.Seat.ALL, 0),
                    VoiceCommand.SetSteeringWheelHeating(0),
                    VoiceCommand.SetTemperature(22),
                    VoiceCommand.SetFanLevel(2),
                    VoiceCommand.SetSeatVentilation(VoiceCommand.Seat.ALL, 1)
                ),
                spokenSummary = "Готово. Включено мягкое охлаждение: климат 22 градуса, обдув 2, вентиляция сидений 1"
            )
        }
    }

    private fun looksLikeQuestion(text: String): Boolean = QUESTION_MARKERS.any(text::contains)

    private fun containsNegatedComplaint(text: String): Boolean =
        NEGATED_COMPLAINT_REGEX.containsMatchIn(text)

    private fun containsUnrelatedExplicitCommand(text: String): Boolean {
        if (EXPLICIT_COMMAND_MARKERS.none(text::contains)) return false
        val request = (HOT_REQUESTS + COLD_REQUESTS).any { phrase ->
            text == phrase || text == "пожалуйста $phrase" || text == "$phrase пожалуйста" ||
                text == "будь ласка $phrase" || text == "$phrase будь ласка"
        }
        return !request
    }

    private fun normalize(raw: String): String = raw
        .trim()
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        val PERSONAL_MARKERS = listOf(
            "мне ", "меня ", "я ", "мною ",
            "мені ", "мене ", "у салоне", "в салоне", "у машині", "в машине"
        )

        val HOT_MARKERS = listOf(
            "жарк", "душн", "спекот", "перегрел", "перегрів", "плавлюсь", "плавлюся"
        )
        val COLD_MARKERS = listOf(
            "холодн", "зябк", "мерз", "змерз", "замерз", "замерза", "окочен"
        )

        val HOT_REQUESTS = listOf(
            "сделай прохладнее", "сделай холоднее", "охлади салон", "хочу прохлады",
            "зроби прохолодніше", "охолоди салон", "хочу прохолоди"
        )
        val COLD_REQUESTS = listOf(
            "сделай теплее", "согрей салон", "хочу тепла",
            "зроби тепліше", "зігрій салон", "хочу тепла"
        )

        val INTENSITY_MARKERS = listOf(
            "очень ", "очен ", "сильно ", "слишком ", "ужасно ", "невыносимо ", "максимально ",
            "дуже ", "надто ", "страшенно ", "нестерпно "
        )
        val VERY_HOT_MARKERS = listOf(
            "перегреваюсь", "перегріваюсь", "плавлюсь", "плавлюся"
        )
        val VERY_COLD_MARKERS = listOf(
            "замерзаю", "змерзаю", "окоченел", "окляк"
        )

        val NEGATED_COMPLAINT_REGEX = Regex(
            "(?:^|\\s)не\\s+(?:(?:очень|дуже|так)\\s+)?" +
                "(?:жарко|душно|холодно|спекотно)(?:$|\\s)"
        )

        val CHAIN_SEPARATOR_REGEX = Regex(
            "\\s+(?:и|та|а\\s+потом|потом|затем|після\\s+цього|потім|далі)\\s+"
        )
        val EXPLICIT_COMMAND_MARKERS = listOf(
            "включ", "выключ", "отключ", "открой", "закрой", "найди", "покажи",
            "пролож", "построй", "веди", "постав", "сделай", "подогр", "согрей",
            "охлад", "проветр", "запуст", "добав", "убав", "увелич", "уменьш",
            "увімк", "вимк", "відключ", "відкрий", "закрий", "знайди", "покажи",
            "проклади", "побудуй", "зроби", "підігр", "зігр", "охолод", "провітр",
            "запусти", "додай", "зменш", "збільш"
        )

        val QUESTION_MARKERS = listOf(
            "почему ", "чому ", "зачем ", "навіщо ", "что значит", "що означає",
            "какая температура", "яка температура"
        )
    }
}
