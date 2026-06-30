package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.google.GoogleTasksApiTools
import com.tombo.billyassistant.companion.google.GoogleTasksResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class GoogleTasksCompanionTool(
    private val tasksApiTools: GoogleTasksApiTools,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "list_google_tasks")
            .put("description", "List Google Tasks from the user's default task list.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "show_completed" to booleanSchema("Whether to include completed tasks. Defaults to false."),
                        "due_max_millis" to integerSchema("Optional due date upper bound as Unix epoch milliseconds."),
                        "max_results" to integerSchema("Maximum number of tasks to return. Defaults to 10."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "create_google_task")
            .put("description", "Create a Google Task in the user's default Google Tasks list and verify it by reading it back.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("title"),
                    properties = mapOf(
                        "title" to stringSchema("Task title."),
                        "notes" to stringSchema("Optional task notes."),
                        "due_millis" to integerSchema("Optional due date as Unix epoch milliseconds."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "complete_google_task")
            .put("description", "Mark an existing Google Task completed. Use this for requests like complete, mark done, finish, or check off a task; do not answer by only listing tasks.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "title_or_id" to stringSchema("Task title, partial title, or exact task id to complete. Leave blank only if the user's intent identifies the single open task."),
                        "task_list_id" to stringSchema("Optional Google Tasks task list id."),
                        "task_id" to stringSchema("Optional exact Google Tasks task id."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "list_google_tasks" -> tasksApiTools.listTasks(
                showCompleted = args.optBoolean("show_completed", false),
                dueMaxMillis = args.optionalLong("due_max_millis"),
                maxResults = args.optionalInt("max_results") ?: 10,
            ).toExecution(finalOnSuccess = true)
            "create_google_task" -> tasksApiTools.createTask(
                title = args.optString("title"),
                notes = args.optString("notes").ifBlank { null },
                dueMillis = args.optionalLong("due_millis"),
            ).toExecution(finalOnSuccess = true)
            "complete_google_task" -> tasksApiTools.completeTask(
                titleOrId = args.optString("title_or_id").ifBlank { null },
                taskListId = args.optString("task_list_id").ifBlank { null },
                taskId = args.optString("task_id").ifBlank { null },
            ).toExecution(finalOnSuccess = true)
            else -> null
        }
    }
}

private fun GoogleTasksResult.toExecution(finalOnSuccess: Boolean = false): CompanionToolExecution {
    val response = toJson()
    if (response.optString("status") == "needs_clarification") {
        val tasks = response.optJSONArray("tasks") ?: JSONArray()
        val pending = mutableListOf<PendingTaskCompletion>()
        val labels = mutableListOf<String>()
        for (i in 0 until minOf(tasks.length(), 4)) {
            val task = tasks.optJSONObject(i) ?: continue
            val title = task.optString("title").ifBlank { "(untitled task)" }
            pending += PendingTaskCompletion(
                taskListId = task.optString("task_list_id"),
                taskListTitle = task.optString("task_list_title"),
                taskId = task.optString("task_id"),
                title = title,
            )
            labels += title
        }
        if (pending.isNotEmpty()) {
            val token = PendingTaskCompletions.put(pending)
            return CompanionToolExecution(
                response = response.put("task_complete_token", token),
                clarificationCard = ClarificationCard(
                    question = response.optString("summary").ifBlank { "Which task?" },
                    context = "task_complete_token=$token",
                    options = labels,
                ),
            )
        }
    }
    return CompanionToolExecution(
        response = response,
        finalText = if (finalOnSuccess || response.optString("status") != "ok") tasksWatchSummary(response, summary) else null,
    )
}

private fun GoogleTasksResult.toJson(): JSONObject {
    return when (this) {
        is GoogleTasksResult.Success -> payload
        is GoogleTasksResult.NeedsScope -> JSONObject()
            .put("status", "needs_scope")
            .put("summary", summary)
            .put("missing_scopes", JSONArray(scopes))
        is GoogleTasksResult.Rejected -> JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        is GoogleTasksResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
    }
}

private fun tasksWatchSummary(response: JSONObject, fallback: String): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { fallback }
    }
    val listTitle = response.optString("task_list_title").ifBlank { "Google Tasks" }
    response.optJSONObject("task")?.let { task ->
        val title = task.optString("title").ifBlank { "(untitled task)" }
        val due = task.optString("due").takeIf { it.isNotBlank() }?.let { dueLabel(it) }.orEmpty()
        return if (task.optString("status") == "completed") {
            "Completed task in $listTitle:\n- $title"
        } else {
            "Created task in $listTitle:\n- $title$due"
        }
    }
    val tasks = response.optJSONArray("tasks") ?: JSONArray()
    if (tasks.length() == 0) {
        return "No open tasks in $listTitle."
    }
    val limit = minOf(tasks.length(), 5)
    val lines = mutableListOf("$listTitle:")
    for (i in 0 until limit) {
        val task = tasks.optJSONObject(i) ?: continue
        val title = task.optString("title").ifBlank { "(untitled task)" }
        val due = task.optString("due").takeIf { it.isNotBlank() }?.let { dueLabel(it) }.orEmpty()
        lines += "- $title$due"
    }
    if (tasks.length() > limit) {
        lines += "- +${tasks.length() - limit} more"
    }
    return lines.joinToString("\n")
}

private fun dueLabel(value: String): String {
    return runCatching {
        val date = Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate()
        " due ${TASK_DATE_FORMAT.format(date)}"
    }.getOrDefault("")
}

private fun JSONObject.optionalLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private val TASK_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
