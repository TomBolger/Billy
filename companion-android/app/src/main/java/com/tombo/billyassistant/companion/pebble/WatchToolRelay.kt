package com.tombo.billyassistant.companion.pebble

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs Billy's watch-owned tools (alarms, timers, reminders, settings, number
 * card) through the Pebble phone JS, which owns their implementation.
 *
 * Android cannot message the phone JS directly, so the watch bounces the
 * request out to the phone JS and bounces the result back to us:
 *
 *   Android -JS_TOOL_REQUEST-> watch -> phone JS -JS_TOOL_RESULT-> watch -> Android
 *
 * [call] blocks the calling (IO) thread until the result arrives or times out.
 */
class WatchToolRelay(
    private val context: Context,
    private val watch: WatchIdentifier,
    private val requestId: Int? = null,
) {
    fun call(name: String, args: JSONObject, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val payload = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("request_id", requestId)
            .put("args", args)
            .toString()
        if (payload.length > MAX_PAYLOAD_CHARS) {
            return error("That request is too long for the watch relay.")
        }
        val future = WatchToolRelayResults.register(id)
        val sent = runBlocking {
            val sender = DefaultPebbleSender(context)
            try {
                val results = sender.sendDataToPebble(
                    BillyPebbleProtocol.APP_UUID,
                    buildMap {
                        put(BillyPebbleProtocol.JS_TOOL_REQUEST, PebbleDictionaryItem.Text(payload))
                        requestId?.let { put(BillyPebbleProtocol.RESPONSE_REQUEST_ID, PebbleDictionaryItem.UInt32(it.toUInt())) }
                    },
                    listOf(watch),
                )
                results?.values?.all { it is TransmissionResult.Success } ?: false
            } catch (e: Exception) {
                Log.w(TAG, "Relay send failed", e)
                false
            } finally {
                sender.close()
            }
        }
        if (!sent) {
            WatchToolRelayResults.cancel(id)
            return error("Could not reach the watch to run $name. Is Billy open on the watch?")
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            WatchToolRelayResults.cancel(id)
            error("The watch did not confirm $name in time. It may not have been applied; check with the matching list tool before retrying.")
        } catch (e: Exception) {
            WatchToolRelayResults.cancel(id)
            error("Watch relay failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun error(summary: String): JSONObject {
        return JSONObject().put("status", "error").put("summary", summary)
    }

    companion object {
        private const val TAG = "WatchToolRelay"
        private const val DEFAULT_TIMEOUT_MS = 9_000L
        private const val MAX_PAYLOAD_CHARS = 900
    }
}

/** Pending relay calls, completed by [BillyPebbleListenerService] when JS_TOOL_RESULT arrives. */
object WatchToolRelayResults {
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()

    fun register(id: String): CompletableFuture<JSONObject> {
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        return future
    }

    fun cancel(id: String) {
        pending.remove(id)
    }

    fun complete(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = json.optString("id")
        val result = json.optJSONObject("result")
            ?: JSONObject().put("status", "error").put("summary", "Empty result from the watch.")
        pending.remove(id)?.complete(result)
    }
}
