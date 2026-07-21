package com.aas.app.commands

/**
 * Produces a complete command plan without network access whenever possible.
 * It never returns a partial multi-command plan: if one actionable clause is
 * unknown, the caller must send the original transcript to the AI planner.
 */
class LocalCommandPlanner(private val parser: CommandParser) {
    private val comfortPresetPlanner = SmartComfortPresetPlanner()

    data class Decision(
        val commands: List<VoiceCommand>,
        val complete: Boolean,
        val technicalMessage: String,
        val spokenSummary: String? = null
    )

    private data class LocalUnit(
        val commands: List<VoiceCommand>,
        val smartProfile: SmartComfortPresetPlanner.Profile? = null,
        val spokenSummary: String? = null
    )

    fun plan(transcript: String): Decision {
        // A pure natural-language comfort complaint is one local atomic preset.
        comfortPresetPlanner.plan(transcript)?.let { preset ->
            return smartDecision(preset)
        }

        val fullCommand = parser.parse(transcript)
        val clauses = splitCommandClauses(transcript)

        if (clauses.size >= 2) {
            val parsedClauses = clauses.map { clause -> clause to parseLocalUnit(clause) }
            val allRecognized = parsedClauses.all { it.second != null }

            if (allRecognized) {
                val units = parsedClauses.mapNotNull { it.second }
                val commands = units.flatMap { it.commands }
                val smartProfiles = units.mapNotNull { it.smartProfile }
                return Decision(
                    commands = commands,
                    complete = true,
                    technicalMessage = buildString {
                        append("route=local-chain; clauses=${clauses.size}; commands=${commands.size}; ai=false")
                        if (smartProfiles.isNotEmpty()) append("; smartProfiles=${smartProfiles.joinToString()}")
                    },
                    spokenSummary = units.singleOrNull()?.spokenSummary
                )
            }

            // Do not split artist/song titles merely because they contain "и"/"та".
            // A failed chain is escalated only when at least two clauses look actionable.
            val actionableClauses = parsedClauses.count { (clause, unit) ->
                unit != null || containsCommandVerb(clause)
            }
            if (actionableClauses >= 2) {
                val unknown = parsedClauses
                    .filter { it.second == null }
                    .joinToString(" | ") { it.first }
                return Decision(
                    commands = emptyList(),
                    complete = false,
                    technicalMessage = "route=ai; reason=incomplete-local-chain; unknown=$unknown"
                )
            }
        }

        if (fullCommand != null) {
            return Decision(
                commands = listOf(fullCommand),
                complete = true,
                technicalMessage = "route=local-direct; command=${fullCommand::class.simpleName}; ai=false"
            )
        }

        return Decision(
            commands = emptyList(),
            complete = false,
            technicalMessage = "route=ai; reason=local-parser-no-match"
        )
    }

    private fun parseLocalUnit(clause: String): LocalUnit? {
        comfortPresetPlanner.plan(clause)?.let { preset ->
            return LocalUnit(
                commands = preset.commands,
                smartProfile = preset.profile,
                spokenSummary = preset.spokenSummary
            )
        }
        return parser.parse(clause)?.let { LocalUnit(listOf(it)) }
    }

    private fun smartDecision(preset: SmartComfortPresetPlanner.Plan): Decision = Decision(
        commands = preset.commands,
        complete = true,
        technicalMessage = "route=local-smart-comfort; profile=${preset.profile}; commands=${preset.commands.size}; ai=false",
        spokenSummary = preset.spokenSummary
    )

    private fun splitCommandClauses(raw: String): List<String> = raw
        .replace(Regex("[;,.]+"), " | ")
        .replace(
            Regex(
                "\\s+(?:а\\s+)?(?:потом|затем|дальше|після\\s+цього|потім|далі)\\s+",
                RegexOption.IGNORE_CASE
            ),
            " | "
        )
        .replace(Regex("\\s+(?:и|та)\\s+", RegexOption.IGNORE_CASE), " | ")
        .split('|')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun containsCommandVerb(raw: String): Boolean {
        val normalized = raw.lowercase()
        return COMMAND_VERB_MARKERS.any(normalized::contains)
    }

    private companion object {
        val COMMAND_VERB_MARKERS = listOf(
            "включ", "выключ", "отключ", "открой", "закрой", "постав", "сделай",
            "подогр", "согрей", "охлад", "проветр", "запуст", "найди", "покажи",
            "пролож", "построй", "веди", "поехал", "добав", "убав", "увелич", "уменьш",
            "увімк", "вимк", "відключ", "відкрий", "закрий", "зроби", "підігр",
            "зігр", "охолод", "провітр", "запусти", "знайди", "покажи", "проклади",
            "побудуй", "веди", "поїхал", "додай", "зменш", "збільш"
        )
    }
}
