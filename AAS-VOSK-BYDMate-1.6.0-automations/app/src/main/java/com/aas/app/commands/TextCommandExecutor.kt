package com.aas.app.commands

import android.content.Context
import com.aas.app.AppPrefs
import com.aas.app.R
import com.aas.app.ai.OpenAiCommandPlanner

/**
 * Shared text-command pipeline used by both live voice recognition and automations.
 * Local parsing is always attempted first; OpenAI is only a fallback for an
 * incomplete or unknown local plan.
 */
class TextCommandExecutor(
    context: Context,
    private val prefs: AppPrefs,
    private val dispatcher: CommandDispatcher,
    private val aiPlanner: OpenAiCommandPlanner,
) {
    private val appContext = context.applicationContext
    private val parser = CommandParser { prefs.seatMaxLevel }
    private val localPlanner = LocalCommandPlanner(parser)

    fun preview(transcript: String): LocalCommandPlanner.Decision = localPlanner.plan(transcript)

    fun execute(transcript: String, allowAiFallback: Boolean = true): ExecutionResult {
        val localPlan = localPlanner.plan(transcript)
        var plannerTechnical = localPlan.technicalMessage
        var localSpokenSummary: String? = localPlan.spokenSummary

        val commands: List<VoiceCommand> = when {
            localPlan.commands.isNotEmpty() && localPlan.complete -> localPlan.commands
            allowAiFallback && aiPlanner.isConfigured() -> {
                localSpokenSummary = null
                try {
                    val plan = aiPlanner.plan(transcript)
                    plannerTechnical = "${localPlan.technicalMessage}; AI fallback: ${plan.technicalMessage}"
                    plan.commands
                } catch (error: Exception) {
                    plannerTechnical = "${localPlan.technicalMessage}; AI fallback failed: ${error.message}"
                    emptyList()
                }
            }
            else -> {
                localSpokenSummary = null
                plannerTechnical = "${localPlan.technicalMessage}; AI unavailable or disabled for automation"
                emptyList()
            }
        }

        if (commands.isEmpty()) {
            val message = appContext.getString(R.string.unsupported_command)
            return ExecutionResult(false, message, "$message; $plannerTechnical")
        }

        val results = mutableListOf<ExecutionResult>()
        for ((index, command) in commands.withIndex()) {
            val result = runCatching { dispatcher.execute(command) }
                .getOrElse {
                    ExecutionResult(
                        false,
                        appContext.getString(R.string.execution_error),
                        it.stackTraceToString(),
                    )
                }
            results += result
            if (index < commands.lastIndex) Thread.sleep(COMMAND_GAP_MS)
        }

        val allSucceeded = results.all { it.success }
        val completed = results.count { it.success }
        val summary = when {
            commands.size == 1 -> results.first().spokenMessage
            allSucceeded -> localSpokenSummary ?: "Выполнено команд: ${commands.size}"
            else -> "Выполнено $completed из ${commands.size} команд"
        }
        val technical = buildString {
            append(plannerTechnical)
            results.forEachIndexed { index, item ->
                append("\n#")
                append(index + 1)
                append(' ')
                append(commands[index]::class.simpleName ?: commands[index].toString())
                append(": success=")
                append(item.success)
                append("; ")
                append(item.technicalMessage)
            }
        }
        return ExecutionResult(allSucceeded, summary, technical)
    }

    private companion object {
        const val COMMAND_GAP_MS = 220L
    }
}
