package com.tombo.billyassistant.companion.google

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

class GoogleTasksApiTools(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun listTasks(showCompleted: Boolean = false, dueMaxMillis: Long? = null, maxResults: Int = 10): GoogleTasksResult {
        return withToken { token ->
            val taskList = defaultTaskList(token) ?: return@withToken GoogleTasksResult.Failed("Google Tasks API returned zero task lists for this account. Open Google Tasks once, then try again.")
            val limit = maxResults.coerceIn(1, MAX_RESULTS)
            val params = mutableListOf(
                "showCompleted=$showCompleted",
                "showHidden=false",
                "maxResults=$limit",
            )
            if (dueMaxMillis != null) {
                params += "dueMax=${encode(Instant.ofEpochMilli(dueMaxMillis).toString())}"
            }
            val url = "$API_BASE/lists/${encode(taskList.id)}/tasks?${params.joinToString("&")}"
            when (val result = http.get(url, token)) {
                is GoogleHttpResult.Success -> {
                    val tasks = JSONObject(result.body).optJSONArray("items") ?: JSONArray()
                    val summary = when (tasks.length()) {
                        0 -> "No Google Tasks found in ${taskList.title}."
                        1 -> "Found 1 Google Task in ${taskList.title}."
                        else -> "Found ${tasks.length()} Google Tasks in ${taskList.title}."
                    }
                    GoogleTasksResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("task_list_id", taskList.id)
                            .put("task_list_title", taskList.title)
                            .put("tasks", compactTasks(tasks, limit)),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleTasksResult.Failed("Google Tasks HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleTasksResult.Failed("Google Tasks failed: ${result.reason}")
            }
        }
    }

    fun createTask(title: String, notes: String?, dueMillis: Long?): GoogleTasksResult {
        if (title.isBlank()) {
            return GoogleTasksResult.Rejected("Task title is blank.")
        }
        return withToken { token ->
            val taskList = defaultTaskList(token) ?: return@withToken GoogleTasksResult.Failed("Google Tasks API returned zero task lists for this account. Open Google Tasks once, then try again.")
            val body = JSONObject().put("title", title.trim().take(MAX_TEXT_LENGTH))
            notes?.trim()?.takeIf { it.isNotBlank() }?.let { body.put("notes", it.take(MAX_TEXT_LENGTH)) }
            dueMillis?.let { body.put("due", Instant.ofEpochMilli(it).toString()) }

            when (val createResult = http.post("$API_BASE/lists/${encode(taskList.id)}/tasks", token, body)) {
                is GoogleHttpResult.Success -> {
                    val created = JSONObject(createResult.body)
                    val taskId = created.optString("id")
                    val readback = if (taskId.isNotBlank()) {
                        http.get("$API_BASE/lists/${encode(taskList.id)}/tasks/${encode(taskId)}", token)
                    } else {
                        GoogleHttpResult.Failed("Google Tasks did not return a task id.")
                    }
                    when (readback) {
                        is GoogleHttpResult.Success -> {
                            val verified = JSONObject(readback.body)
                            val summary = "Created Google Task \"${verified.optString("title", title.trim())}\" in ${taskList.title}, task id ${verified.optString("id", taskId)}."
                            GoogleTasksResult.Success(
                                summary = summary,
                                payload = JSONObject()
                                    .put("status", "ok")
                                    .put("summary", summary)
                                    .put("task_id", verified.optString("id", taskId))
                                    .put("task_list_id", taskList.id)
                                    .put("task_list_title", taskList.title)
                                    .put("verified", true)
                                    .put("task", compactTask(verified)),
                            )
                        }
                        is GoogleHttpResult.HttpError -> GoogleTasksResult.Failed("Created task, but readback failed with HTTP ${readback.responseCode}: ${readback.reason}")
                        is GoogleHttpResult.Failed -> GoogleTasksResult.Failed("Created task, but readback failed: ${readback.reason}")
                    }
                }
                is GoogleHttpResult.HttpError -> GoogleTasksResult.Failed("Google Tasks HTTP ${createResult.responseCode}: ${createResult.reason}")
                is GoogleHttpResult.Failed -> GoogleTasksResult.Failed("Google Tasks failed: ${createResult.reason}")
            }
        }
    }

    fun completeTask(
        titleOrId: String?,
        taskListId: String? = null,
        taskId: String? = null,
    ): GoogleTasksResult {
        return withToken { token ->
            val lists = if (!taskListId.isNullOrBlank()) {
                listOf(TaskListSummary(id = taskListId, title = "selected task list"))
            } else {
                taskLists(token)
            }
            if (lists.isEmpty()) {
                return@withToken GoogleTasksResult.Failed("Google Tasks API returned zero task lists for this account. Open Google Tasks once, then try again.")
            }

            val openTasks = lists.flatMap { list ->
                tasksForList(token, list, showCompleted = false, maxResults = COMPLETE_SEARCH_LIMIT)
            }
            val selected = selectTask(openTasks, titleOrId, taskId)
            when {
                selected.isEmpty() -> GoogleTasksResult.Rejected(
                    if (titleOrId.isNullOrBlank()) {
                        "I could not identify which open Google Task to complete."
                    } else {
                        "No open Google Task matched \"$titleOrId\"."
                    },
                )
                selected.size > 1 -> {
                    val summary = "Which Google Task should I complete?"
                    GoogleTasksResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "needs_clarification")
                            .put("summary", summary)
                            .put("tasks", JSONArray().also { array ->
                                selected.take(MAX_CHOICE_RESULTS).forEach { candidate ->
                                    array.put(candidate.toJson())
                                }
                            }),
                    )
                }
                else -> completeExactTask(token, selected.first())
            }
        }
    }

    fun completeExactTask(taskListId: String, taskId: String): GoogleTasksResult {
        return withToken { token ->
            val lists = taskLists(token)
            val list = lists.firstOrNull { it.id == taskListId } ?: TaskListSummary(taskListId, "Google Tasks")
            val task = taskForId(token, list, taskId)
                ?: TaskCandidate(taskListId = taskListId, taskListTitle = list.title, taskId = taskId, title = "Google Task", due = "")
            completeExactTask(token, task)
        }
    }

    private fun completeExactTask(token: String, candidate: TaskCandidate): GoogleTasksResult {
        val body = JSONObject()
            .put("status", "completed")
            .put("completed", Instant.now().toString())
        val url = "$API_BASE/lists/${encode(candidate.taskListId)}/tasks/${encode(candidate.taskId)}"
        return when (val result = http.patch(url, token, body)) {
            is GoogleHttpResult.Success -> {
                val verifiedResult = http.get(url, token)
                val verified = if (verifiedResult is GoogleHttpResult.Success) {
                    JSONObject(verifiedResult.body)
                } else {
                    JSONObject(result.body)
                }
                val title = verified.optString("title").ifBlank { candidate.title }
                val summary = "Completed Google Task in ${candidate.taskListTitle}:\n- $title"
                GoogleTasksResult.Success(
                    summary = summary,
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", summary)
                        .put("task_list_id", candidate.taskListId)
                        .put("task_list_title", candidate.taskListTitle)
                        .put("task_id", candidate.taskId)
                        .put("verified", verified.optString("status") == "completed")
                        .put("task", compactTask(verified)),
                )
            }
            is GoogleHttpResult.HttpError -> GoogleTasksResult.Failed("Google Tasks complete HTTP ${result.responseCode}: ${result.reason}")
            is GoogleHttpResult.Failed -> GoogleTasksResult.Failed("Google Tasks complete failed: ${result.reason}")
        }
    }

    private fun defaultTaskList(token: String): TaskListSummary? {
        return taskLists(token).firstOrNull()
    }

    private fun taskLists(token: String): List<TaskListSummary> {
        return when (val result = http.get("$API_BASE/users/@me/lists?maxResults=20", token)) {
            is GoogleHttpResult.Success -> {
                val lists = JSONObject(result.body).optJSONArray("items") ?: JSONArray()
                buildList {
                    for (i in 0 until lists.length()) {
                        val item = lists.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (id.isNotBlank()) {
                            add(
                                TaskListSummary(
                                    id = id,
                                    title = item.optString("title", "Google Tasks"),
                                ),
                            )
                        }
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun tasksForList(
        token: String,
        list: TaskListSummary,
        showCompleted: Boolean,
        maxResults: Int,
    ): List<TaskCandidate> {
        val url = "$API_BASE/lists/${encode(list.id)}/tasks?showCompleted=$showCompleted&showHidden=false&maxResults=${maxResults.coerceIn(1, COMPLETE_SEARCH_LIMIT)}"
        return when (val result = http.get(url, token)) {
            is GoogleHttpResult.Success -> {
                val tasks = JSONObject(result.body).optJSONArray("items") ?: JSONArray()
                buildList {
                    for (i in 0 until tasks.length()) {
                        val task = tasks.optJSONObject(i) ?: continue
                        val id = task.optString("id")
                        val title = task.optString("title")
                        if (id.isNotBlank()) {
                            add(
                                TaskCandidate(
                                    taskListId = list.id,
                                    taskListTitle = list.title,
                                    taskId = id,
                                    title = title,
                                    due = task.optString("due"),
                                ),
                            )
                        }
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun taskForId(token: String, list: TaskListSummary, taskId: String): TaskCandidate? {
        return when (val result = http.get("$API_BASE/lists/${encode(list.id)}/tasks/${encode(taskId)}", token)) {
            is GoogleHttpResult.Success -> {
                val task = JSONObject(result.body)
                TaskCandidate(
                    taskListId = list.id,
                    taskListTitle = list.title,
                    taskId = task.optString("id", taskId),
                    title = task.optString("title").ifBlank { "Google Task" },
                    due = task.optString("due"),
                )
            }
            else -> null
        }
    }

    private fun selectTask(
        candidates: List<TaskCandidate>,
        titleOrId: String?,
        taskId: String?,
    ): List<TaskCandidate> {
        val exactId = taskId?.trim().orEmpty().ifBlank { titleOrId?.trim().orEmpty() }
        if (exactId.isNotBlank()) {
            candidates.firstOrNull { it.taskId == exactId }?.let { return listOf(it) }
        }
        val query = titleOrId?.trim().orEmpty()
        if (query.isBlank()) {
            return if (candidates.size == 1) candidates else candidates.take(MAX_CHOICE_RESULTS)
        }
        val queryWords = query.normalizedWords()
        if (queryWords.isEmpty()) {
            return candidates.take(MAX_CHOICE_RESULTS)
        }
        return candidates
            .map { candidate -> candidate to candidate.matchScore(queryWords, query) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (candidate, _) -> candidate }
            .take(MAX_CHOICE_RESULTS)
    }

    private fun compactTasks(tasks: JSONArray, limit: Int): JSONArray {
        return JSONArray().also { array ->
            for (i in 0 until minOf(tasks.length(), limit, MAX_RESULTS)) {
                tasks.optJSONObject(i)?.let { array.put(compactTask(it)) }
            }
        }
    }

    private fun compactTask(task: JSONObject): JSONObject {
        return JSONObject()
            .put("id", task.optString("id"))
            .put("title", task.optString("title"))
            .put("notes", task.optString("notes"))
            .put("status", task.optString("status"))
            .put("due", task.optString("due"))
            .put("completed", task.optString("completed"))
            .put("updated", task.optString("updated"))
            .put("web_view_link", task.optString("webViewLink"))
    }

    private fun withToken(block: (String) -> GoogleTasksResult): GoogleTasksResult {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.tasks)) {
            is GoogleAccessTokenResult.Authorized -> block(token.accessToken)
            is GoogleAccessTokenResult.NeedsUserGrant -> GoogleTasksResult.NeedsScope(token.scopes)
            is GoogleAccessTokenResult.Failed -> GoogleTasksResult.Failed(token.reason)
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private data class TaskListSummary(
        val id: String,
        val title: String,
    )

    private data class TaskCandidate(
        val taskListId: String,
        val taskListTitle: String,
        val taskId: String,
        val title: String,
        val due: String,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("task_list_id", taskListId)
                .put("task_list_title", taskListTitle)
                .put("task_id", taskId)
                .put("title", title)
                .put("due", due)
                .put("label", label())
        }

        fun label(): String {
            val dueText = due.takeIf { it.isNotBlank() }?.let { " due ${it.take(10)}" }.orEmpty()
            return "$title$dueText"
        }

        fun matchScore(queryWords: Set<String>, rawQuery: String): Int {
            val titleText = title.lowercase()
            val titleWords = title.normalizedWords()
            var score = queryWords.count { it in titleWords } * 10
            if (titleText.contains(rawQuery.lowercase())) {
                score += 30
            }
            return score
        }
    }

    private companion object {
        private const val API_BASE = "https://tasks.googleapis.com/tasks/v1"
        private const val MAX_RESULTS = 20
        private const val COMPLETE_SEARCH_LIMIT = 40
        private const val MAX_CHOICE_RESULTS = 6
        private const val MAX_TEXT_LENGTH = 4_096
    }
}

private fun String.normalizedWords(): Set<String> {
    return lowercase()
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 2 && it !in TASK_STOP_WORDS }
        .toSet()
}

private val TASK_STOP_WORDS = setOf(
    "task",
    "tasks",
    "complete",
    "completed",
    "mark",
    "done",
    "finish",
    "finished",
    "the",
    "my",
    "a",
    "an",
)

sealed interface GoogleTasksResult {
    val summary: String

    data class Success(
        override val summary: String,
        val payload: JSONObject,
    ) : GoogleTasksResult

    data class NeedsScope(val scopes: List<String>) : GoogleTasksResult {
        override val summary: String = "Grant Google Tasks access in the companion app."
    }

    data class Rejected(val reason: String) : GoogleTasksResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : GoogleTasksResult {
        override val summary: String = reason
    }
}
