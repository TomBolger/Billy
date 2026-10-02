package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.google.GoogleApiHttp
import com.tombo.billyassistant.companion.google.GoogleHttpResult
import org.json.JSONObject

/** Small helper for tools that call Google REST APIs with the user's sign-in. */
internal class GoogleAccess(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val service: String,
    private val scopes: List<String>,
    val http: GoogleApiHttp = GoogleApiHttp(),
) {
    /** Runs [block] with a token, or returns a needs_sign_in / error result. */
    fun run(block: (token: String) -> JSONObject): JSONObject {
        return when (val token = tokenProvider.getAccessToken(scopes)) {
            is GoogleAccessTokenResult.Authorized -> try {
                block(token.accessToken)
            } catch (e: GoogleCallException) {
                error(e.message ?: "$service request failed.")
            }
            is GoogleAccessTokenResult.NeedsUserGrant -> JSONObject()
                .put("status", "needs_sign_in")
                .put("summary", "Open Billy Companion and tap Connect Google to allow $service.")
            is GoogleAccessTokenResult.Failed -> error("Google sign-in failed: ${token.reason}")
        }
    }

    fun get(url: String, token: String): JSONObject = unwrap(http.get(url, token))
    fun post(url: String, token: String, body: JSONObject): JSONObject = unwrap(http.post(url, token, body))
    fun patch(url: String, token: String, body: JSONObject): JSONObject = unwrap(http.patch(url, token, body))
    fun delete(url: String, token: String) {
        unwrap(http.delete(url, token))
    }

    private fun unwrap(result: GoogleHttpResult): JSONObject {
        return when (result) {
            is GoogleHttpResult.Success -> if (result.body.isBlank()) JSONObject() else JSONObject(result.body)
            is GoogleHttpResult.HttpError -> throw GoogleCallException(
                when (result.responseCode) {
                    401, 403 -> "$service refused the request (${result.reason.take(100)}). Reconnect Google in Billy Companion."
                    404 -> "$service could not find that item. It may already be gone."
                    410 -> "That $service item was already deleted."
                    429 -> "$service is rate limiting Billy. Try again in a minute."
                    else -> "$service error ${result.responseCode}: ${result.reason.take(120)}"
                },
            )
            is GoogleHttpResult.Failed -> throw GoogleCallException("Could not reach $service: ${result.reason.take(100)}")
        }
    }

    companion object {
        fun ok(summary: String): JSONObject = JSONObject().put("status", "ok").put("summary", summary)
        fun error(summary: String): JSONObject = JSONObject().put("status", "error").put("summary", summary)
        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

internal class GoogleCallException(message: String) : Exception(message)
