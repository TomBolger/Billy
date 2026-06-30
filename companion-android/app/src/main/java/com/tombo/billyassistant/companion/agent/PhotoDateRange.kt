package com.tombo.billyassistant.companion.agent

internal data class PhotoDateRange(
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
    val matchedText: String,
)
