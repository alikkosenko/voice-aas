package com.aas.app.vehicle

import com.aas.app.commands.ExecutionResult
import com.aas.app.commands.VoiceCommand

interface VehicleAdapter {
    fun diagnosticStatus(): String
    fun execute(command: VoiceCommand.Vehicle): ExecutionResult
}
