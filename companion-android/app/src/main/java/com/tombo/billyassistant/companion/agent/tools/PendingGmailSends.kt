package com.tombo.billyassistant.companion.agent.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PendingGmailSends {
    private val pending = ConcurrentHashMap<String, PendingGmailSend>()

    fun put(send: PendingGmailSend): String {
        val token = UUID.randomUUID().toString()
        pending[token] = send
        return token
    }

    fun resolve(token: String): PendingGmailSend? {
        return pending.remove(token)
    }
}

data class PendingGmailSend(
    val to: String,
    val subject: String,
    val body: String,
)
