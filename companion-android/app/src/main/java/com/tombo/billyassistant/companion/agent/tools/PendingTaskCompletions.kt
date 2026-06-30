package com.tombo.billyassistant.companion.agent.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingTaskCompletion(
    val taskListId: String,
    val taskListTitle: String,
    val taskId: String,
    val title: String,
)

object PendingTaskCompletions {
    private val pending = ConcurrentHashMap<String, List<PendingTaskCompletion>>()

    fun put(options: List<PendingTaskCompletion>): String {
        val token = UUID.randomUUID().toString()
        pending[token] = options
        return token
    }

    fun resolve(token: String, answer: String): PendingTaskCompletion? {
        val options = pending.remove(token).orEmpty()
        if (options.isEmpty()) {
            return null
        }
        val cleanAnswer = answer.substringBefore('|').trim()
        return options.firstOrNull { option ->
            option.title.equals(cleanAnswer, ignoreCase = true)
        } ?: options.firstOrNull { option ->
            cleanAnswer.contains(option.title, ignoreCase = true) ||
                option.title.contains(cleanAnswer, ignoreCase = true)
        } ?: options.firstOrNull()
    }
}
