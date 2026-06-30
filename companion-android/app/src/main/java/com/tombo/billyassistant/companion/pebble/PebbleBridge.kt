package com.tombo.billyassistant.companion.pebble

import android.content.Context

class PebbleBridge(private val context: Context) {
    val state: PebbleBridgeState
        get() = PebbleBridgeState.NotConfigured

    fun connect(): PebbleBridgeResult {
        return PebbleBridgeResult.NotAvailable(
            reason = "Pebble transport is handled by the active Billy listener service.",
        )
    }

    fun sendAssistantText(text: String): PebbleBridgeResult {
        if (text.isBlank()) {
            return PebbleBridgeResult.Rejected("Cannot send a blank assistant response.")
        }

        return PebbleBridgeResult.NotAvailable(
            reason = "Pebble transport is handled by the active Billy listener service.",
        )
    }

    @Suppress("unused")
    fun applicationContext(): Context = context.applicationContext
}

sealed interface PebbleBridgeState {
    data object NotConfigured : PebbleBridgeState
    data object Disconnected : PebbleBridgeState
    data object Connected : PebbleBridgeState
}

sealed interface PebbleBridgeResult {
    data object Sent : PebbleBridgeResult
    data class Rejected(val reason: String) : PebbleBridgeResult
    data class NotAvailable(val reason: String) : PebbleBridgeResult
}
