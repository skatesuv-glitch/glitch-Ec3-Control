package com.ec3control.data.stellantis

sealed interface CommandState {
    data object Idle : CommandState
    data object Sending : CommandState
    data class Accepted(val commandId: String?) : CommandState
    data object Confirmed : CommandState
    data class Failed(val reason: String) : CommandState
    data object TimedOut : CommandState
}
