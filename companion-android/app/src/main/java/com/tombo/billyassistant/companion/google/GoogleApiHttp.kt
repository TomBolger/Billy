package com.tombo.billyassistant.companion.google

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GoogleApiHttp {
    fun get(url: String, accessToken: String): GoogleHttpResult {
        return request(method = "GET", url = url, accessToken = accessToken, body = null)
    }

    fun post(url: String, accessToken: String, body: JSONObject): GoogleHttpResult {
        return request(method = "POST", url = url, accessToken = accessToken, body = body)
    }

    fun patch(url: String, accessToken: String, body: JSONObject): GoogleHttpResult {
        return request(method = "PATCH", url = url, accessToken = accessToken, body = body)
    }

    fun delete(url: String, accessToken: String): GoogleHttpResult {
        return request(method = "DELETE", url = url, accessToken = accessToken, body = null)
    }

    private fun request(method: String, url: String, accessToken: String, body: JSONObject?): GoogleHttpResult {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode in 200..299) {
                GoogleHttpResult.Success(responseCode, responseText)
            } else {
                GoogleHttpResult.HttpError(responseCode, extractGoogleError(responseText))
            }
        } catch (e: Exception) {
            GoogleHttpResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun extractGoogleError(responseText: String): String {
        if (responseText.isBlank()) {
            return "empty Google API error response"
        }
        return try {
            JSONObject(responseText)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: responseText.take(MAX_ERROR_LENGTH)
        } catch (_: Exception) {
            responseText.take(MAX_ERROR_LENGTH)
        }
    }

    private companion object {
        private const val TIMEOUT_MS = 8_000
        private const val MAX_ERROR_LENGTH = 220
    }
}

sealed interface GoogleHttpResult {
    data class Success(val responseCode: Int, val body: String) : GoogleHttpResult
    data class HttpError(val responseCode: Int, val reason: String) : GoogleHttpResult
    data class Failed(val reason: String) : GoogleHttpResult
}
