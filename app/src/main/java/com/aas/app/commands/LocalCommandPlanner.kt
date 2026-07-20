package com.aas.app.commands

/**
 * Produces a complete command plan without network access whenever possible.
 * It never returns a partial multi-command plan: if one actionable clause is
 * unknown, the caller must send the original transcript to the AI planner.
 */
class LocalCommandPlanner(private val parser: CommandParser) {
    data class Decision(
        val commands: List<VoiceCommand>,
        val complete: Boolean,
        val technicalMessage: String
    )

    fun plan(transcript: String): Decision {
        val fullCommand = parser.parse(transcript)
        val clauses = splitCommandClauses(transcript)

        if (clauses.size >= 2) {
            val parsedClauses = clauses.map { clause -> clause to parser.parse(clause) }
            val recognized = parsedClauses.mapNotNull { it.second }
            val allRecognized = recognized.size == parsedClauses.size

            if (allRecognized) {
                return Decision(
                    commands = recognized,
                    complete = true,
                    technicalMessage = "route=local-chain; clauses=${clauses.size}; ai=false"
                )
            }

            // Do not split artist/song titles merely because they contain "и"/"та".
            // A failed chain is escalated only when at least two clauses contain
            // explicit command verbs.
            val actionableClauses = clauses.count(::containsCommandVerb)
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
