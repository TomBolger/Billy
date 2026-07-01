package com.tombo.billyassistant.companion.pebble

import android.util.Log
import com.tombo.billyassistant.companion.agent.CompanionAgent
import com.tombo.billyassistant.companion.agent.CompanionAgentResult
import com.tombo.billyassistant.companion.agent.tools.ClarificationCard
import com.tombo.billyassistant.companion.agent.tools.WatchWeatherCurrent
import com.tombo.billyassistant.companion.agent.tools.WatchImage
import com.tombo.billyassistant.companion.agent.tools.WatchMediaSpec
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val CLARIFICATION_DICTATE_OPTION = "Dictate..."

class BillyPebbleListenerService : BasePebbleListenerService() {
    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != BillyPebbleProtocol.APP_UUID) {
            return ReceiveResult.Nack
        }
        PebbleWatchStore(this).saveLastWatch(watch)
        val prompt = data.textValue(BillyPebbleProtocol.PROMPT)
        if (prompt == null) {
            if (data[BillyPebbleProtocol.WATCH_READY] == null) {
                return ReceiveResult.Ack
            }
            val sender = DefaultPebbleSender(this)
            try {
                sender.sendAndroidCompanionReady(watch)
                val pendingPrompt = PendingWatchPromptStore(this).pop()
                if (pendingPrompt != null) {
                    sender.sendPrompt(pendingPrompt, watch)
                }
            } finally {
                sender.close()
            }
            return ReceiveResult.Ack
        }
        val runtime = data.textValue(BillyPebbleProtocol.ASSISTANT_RUNTIME) ?: RUNTIME_AUTOMATIC
        val watchMediaSpec = data.textValue(BillyPebbleProtocol.PROMPT_CONTEXT).toWatchMediaSpec()
        val threadId = data.textValue(BillyPebbleProtocol.THREAD_ID)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        if (runtime == RUNTIME_COMPANIONLESS) {
            Log.d(TAG, "Ignoring prompt because companionless runtime is selected.")
            return ReceiveResult.Ack
        }
        if (prompt.isNativeWatchActionPrompt()) {
            Log.d(TAG, "Ignoring native watch action prompt so the Pebble JS watch tools can handle it.")
            return ReceiveResult.Ack
        }

        Log.d(TAG, "Received Billy prompt from watch: $prompt runtime=$runtime")
        val sender = DefaultPebbleSender(this)
        try {
            sender.sendAndroidCompanionReady(watch)
            sender.sendThreadId(threadId, watch)
            sender.sendFunction("Thinking...", watch)
            val result = withTimeoutOrNull(REMOTE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    CompanionAgent(
                        context = this@BillyPebbleListenerService,
                        watchMediaSpec = watchMediaSpec,
                        threadId = threadId,
                    ).answer(prompt)
                }
            }
            if (result == null) {
                sender.sendChunks("Remote answer timed out.", watch)
                sender.sendDone(watch)
                return ReceiveResult.Ack
            }
            when (result) {
                is CompanionAgentResult.Passed -> {
                    if (result.clarificationCard != null) {
                        result.watchImage?.let { image -> sender.sendWatchImage(image, watch) }
                        result.watchWeatherCurrent?.let { weather -> sender.sendWeatherCurrent(weather, watch) }
                        sender.sendClarificationCard(result.clarificationCard, watch)
                        sender.sendDone(watch)
                        return ReceiveResult.Ack
                    }
                    result.watchImage?.let { image -> sender.sendWatchImage(image, watch) }
                    result.watchWeatherCurrent?.let { weather -> sender.sendWeatherCurrent(weather, watch) }
                    if (result.text.isNotBlank()) {
                        sender.sendChunks(result.text, watch)
                    }
                    sender.sendDone(watch)
                }
                is CompanionAgentResult.Failed -> {
                    sender.sendWarning(result.reason, watch)
                    sender.sendDone(watch)
                }
            }
        } finally {
            sender.close()
        }
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == BillyPebbleProtocol.APP_UUID) {
            PebbleWatchStore(this).saveLastWatch(watch)
            Log.d(TAG, "Billy opened on $watch")
        }
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == BillyPebbleProtocol.APP_UUID) {
            Log.d(TAG, "Billy closed on $watch")
        }
    }

    companion object {
        private const val TAG = "BillyPebbleListener"
        private const val RUNTIME_AUTOMATIC = "automatic"
        private const val RUNTIME_COMPANIONLESS = "companionless"
        private const val REMOTE_TIMEOUT_MS = 45_000L
    }
}

private fun PebbleDictionary.textValue(key: UInt): String? {
    return (this[key] as? PebbleDictionaryItem.Text)?.value
}

private suspend fun DefaultPebbleSender.sendClarificationCard(card: ClarificationCard, watch: WatchIdentifier) {
    val baseOptions = card.options
        .filterNot { it.equals(CLARIFICATION_DICTATE_OPTION, ignoreCase = true) }
        .take(3)
    val options = if (baseOptions.isEmpty()) {
        listOf(CLARIFICATION_DICTATE_OPTION)
    } else {
        baseOptions + CLARIFICATION_DICTATE_OPTION
    }
    val payload = mutableMapOf<UInt, PebbleDictionaryItem>(
        BillyPebbleProtocol.CLARIFY_WIDGET to PebbleDictionaryItem.Int32(1),
        BillyPebbleProtocol.CLARIFY_QUESTION to PebbleDictionaryItem.Text(card.question.take(220)),
        BillyPebbleProtocol.CLARIFY_CONTEXT to PebbleDictionaryItem.Text(card.context.take(560)),
        BillyPebbleProtocol.CLARIFY_OPTION_COUNT to PebbleDictionaryItem.Int32(options.size),
    )
    options.forEachIndexed { index, option ->
        payload[BillyPebbleProtocol.CLARIFY_OPTION_0 + index.toUInt()] = PebbleDictionaryItem.Text(option.take(64))
    }
    sendDataToPebble(BillyPebbleProtocol.APP_UUID, payload, listOf(watch))
}

private suspend fun DefaultPebbleSender.sendWeatherCurrent(weather: WatchWeatherCurrent, watch: WatchIdentifier) {
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(
            BillyPebbleProtocol.WEATHER_WIDGET to PebbleDictionaryItem.Int32(2),
            BillyPebbleProtocol.WEATHER_WIDGET_CURRENT_TEMP to PebbleDictionaryItem.Int32(weather.temperature),
            BillyPebbleProtocol.WEATHER_WIDGET_FEELS_LIKE to PebbleDictionaryItem.Int32(weather.feelsLike),
            BillyPebbleProtocol.WEATHER_WIDGET_LOCATION to PebbleDictionaryItem.Text(weather.location.uppercase().take(28)),
            BillyPebbleProtocol.WEATHER_WIDGET_DAY_SUMMARY to PebbleDictionaryItem.Text(weather.description.take(80)),
            BillyPebbleProtocol.WEATHER_WIDGET_TEMP_UNIT to PebbleDictionaryItem.Text(weather.tempUnit),
            BillyPebbleProtocol.WEATHER_WIDGET_WIND_SPEED to PebbleDictionaryItem.Int32(weather.windSpeed),
            BillyPebbleProtocol.WEATHER_WIDGET_WIND_SPEED_UNIT to PebbleDictionaryItem.Text(weather.windSpeedUnit),
            BillyPebbleProtocol.WEATHER_WIDGET_DAY_ICON to PebbleDictionaryItem.Int32(weather.condition),
        ),
        listOf(watch),
    )
}

private suspend fun DefaultPebbleSender.sendPrompt(prompt: String, watch: WatchIdentifier) {
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(BillyPebbleProtocol.WATCH_PROMPT to PebbleDictionaryItem.Text(prompt.take(240))),
        listOf(watch),
    )
}

private suspend fun DefaultPebbleSender.sendAndroidCompanionReady(watch: WatchIdentifier) {
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(BillyPebbleProtocol.ANDROID_COMPANION_READY to PebbleDictionaryItem.UInt8(1)),
        listOf(watch),
    )
}

private suspend fun DefaultPebbleSender.sendFunction(text: String, watch: WatchIdentifier) {
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(BillyPebbleProtocol.FUNCTION to PebbleDictionaryItem.Text(text)),
        listOf(watch),
    )
}

private suspend fun DefaultPebbleSender.sendThreadId(threadId: String, watch: WatchIdentifier) {
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(BillyPebbleProtocol.THREAD_ID to PebbleDictionaryItem.Text(threadId)),
        listOf(watch),
    )
}

private suspend fun DefaultPebbleSender.sendWarning(text: String, watch: WatchIdentifier) {
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(BillyPebbleProtocol.WARNING to PebbleDictionaryItem.Text(text.forWatch().take(180))),
        listOf(watch),
    )
}

private suspend fun DefaultPebbleSender.sendDone(watch: WatchIdentifier) {
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(BillyPebbleProtocol.CHAT_DONE to PebbleDictionaryItem.UInt8(1)),
        listOf(watch),
    )
}

private suspend fun DefaultPebbleSender.sendChunks(text: String, watch: WatchIdentifier) {
    val normalized = text.forWatch().replace('\u202f', ' ')
    var index = 0
    while (index < normalized.length) {
        val end = minOf(index + BillyPebbleProtocol.CHAT_CHUNK_LENGTH, normalized.length)
        sendDataToPebble(
            BillyPebbleProtocol.APP_UUID,
            mapOf(BillyPebbleProtocol.CHAT to PebbleDictionaryItem.Text(normalized.substring(index, end))),
            listOf(watch),
        )
        index = end
    }
}

private suspend fun DefaultPebbleSender.sendWatchImage(image: WatchImage, watch: WatchIdentifier) {
    val imageId = BillyPebbleProtocol.nextImageId()
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(
            BillyPebbleProtocol.IMAGE_ID to PebbleDictionaryItem.Int32(imageId),
            BillyPebbleProtocol.IMAGE_START_BYTE_SIZE to PebbleDictionaryItem.Int32(image.data.size),
            BillyPebbleProtocol.IMAGE_WIDTH to PebbleDictionaryItem.Int32(image.width),
            BillyPebbleProtocol.IMAGE_HEIGHT to PebbleDictionaryItem.Int32(image.height),
        ),
        listOf(watch),
    )
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(
            BillyPebbleProtocol.MAP_WIDGET to PebbleDictionaryItem.Int32(1),
            BillyPebbleProtocol.MAP_WIDGET_IMAGE_ID to PebbleDictionaryItem.Int32(imageId),
            BillyPebbleProtocol.MAP_WIDGET_USER_LOCATION to PebbleDictionaryItem.Int32(0),
        ),
        listOf(watch),
    )
    kotlinx.coroutines.delay(BillyPebbleProtocol.IMAGE_CHUNK_START_DELAY_MS)
    var offset = 0
    while (offset < image.data.size) {
        val end = minOf(offset + BillyPebbleProtocol.IMAGE_CHUNK_LENGTH, image.data.size)
        sendDataToPebble(
            BillyPebbleProtocol.APP_UUID,
            mapOf(
                BillyPebbleProtocol.IMAGE_ID to PebbleDictionaryItem.Int32(imageId),
                BillyPebbleProtocol.IMAGE_CHUNK_OFFSET to PebbleDictionaryItem.Int32(offset),
                BillyPebbleProtocol.IMAGE_CHUNK_DATA to PebbleDictionaryItem.Bytes(image.data.copyOfRange(offset, end)),
            ),
            listOf(watch),
        )
        offset = end
    }
    sendDataToPebble(
        BillyPebbleProtocol.APP_UUID,
        mapOf(
            BillyPebbleProtocol.IMAGE_ID to PebbleDictionaryItem.Int32(imageId),
            BillyPebbleProtocol.IMAGE_COMPLETE to PebbleDictionaryItem.UInt8(1),
        ),
        listOf(watch),
    )
}

private fun String.forWatch(): String {
    return replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u2022', '-')
        .replace(Regex("^\\s*[*+]\\s+", RegexOption.MULTILINE), "- ")
        .replace(Regex("^\\s*-\\s+", RegexOption.MULTILINE), "- ")
        .replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\*\\*([^*\\n][\\s\\S]*?)\\*\\*"), "$1")
        .replace(Regex("__([^_\\n][\\s\\S]*?)__"), "$1")
        .replace(Regex("(^|[^*])\\*([^*\\n]+)\\*"), "$1$2")
        .replace(Regex("(^|[^_])_([^_\\n]+)_"), "$1$2")
        .replace(Regex("`([^`\\n]+)`"), "$1")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

private fun String.isNativeWatchActionPrompt(): Boolean {
    val text = lowercase()
    val hasWatchAction = Regex("""\b(timer|timers|alarm|alarms|remind|reminder|reminders|wake\s+me)\b""").containsMatchIn(text)
    val hasActionVerb = Regex("""\b(cancel|delete|remove|list|show|check|get|create|add|make|schedule|start|set)\b""").containsMatchIn(text)
    return hasWatchAction && (hasActionVerb || "wake me" in text || "remind me" in text)
}

private fun String?.toWatchMediaSpec(): WatchMediaSpec {
    if (isNullOrBlank()) {
        return WatchMediaSpec.Default
    }
    val mediaMatch = Regex("""media=(\d+)x(\d+)""").find(this) ?: return WatchMediaSpec.Default
    val pbiMatch = Regex("""pbi=(\d+)""").find(this)
    val maxBytesMatch = Regex("""maxb=(\d+)""").find(this)
    val maxWidth = mediaMatch.groupValues[1].toIntOrNull()?.coerceIn(80, 200) ?: WatchMediaSpec.Default.maxWidth
    val maxHeight = mediaMatch.groupValues[2].toIntOrNull()?.coerceIn(60, 220) ?: WatchMediaSpec.Default.maxHeight
    val pbiDepth = when (pbiMatch?.groupValues?.getOrNull(1)?.toIntOrNull()) {
        4 -> 4
        2 -> 2
        else -> WatchMediaSpec.Default.pbiDepth
    }
    val maxBytes = maxBytesMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(4_000, 60_000)
        ?: WatchMediaSpec.Default.maxBytes
    return WatchMediaSpec(maxWidth = maxWidth, maxHeight = maxHeight, pbiDepth = pbiDepth, maxBytes = maxBytes)
}

object BillyPebbleProtocol {
    val APP_UUID: UUID = UUID.fromString("f74b42bb-3473-444f-9722-dd34136d9b02")
    const val CHAT_CHUNK_LENGTH = 80
    const val IMAGE_CHUNK_LENGTH = 500
    const val IMAGE_CHUNK_START_DELAY_MS = 75L
    private val imageIds = AtomicInteger(10_000)

    val CHAT: UInt = 10030u
    val FUNCTION: UInt = 10031u
    val PROMPT: UInt = 10032u
    val PROMPT_CONTEXT: UInt = 10033u
    val CHAT_DONE: UInt = 10037u
    val THREAD_ID: UInt = 10038u
    val WARNING: UInt = 10054u
    val WEATHER_WIDGET: UInt = 10055u
    val WEATHER_WIDGET_DAY_ICON: UInt = 10058u
    val WEATHER_WIDGET_LOCATION: UInt = 10059u
    val WEATHER_WIDGET_DAY_SUMMARY: UInt = 10060u
    val WEATHER_WIDGET_TEMP_UNIT: UInt = 10061u
    val WEATHER_WIDGET_CURRENT_TEMP: UInt = 10063u
    val WEATHER_WIDGET_FEELS_LIKE: UInt = 10064u
    val WEATHER_WIDGET_WIND_SPEED: UInt = 10065u
    val WEATHER_WIDGET_WIND_SPEED_UNIT: UInt = 10066u
    val IMAGE_WIDTH: UInt = 10095u
    val IMAGE_HEIGHT: UInt = 10096u
    val IMAGE_START_BYTE_SIZE: UInt = 10097u
    val IMAGE_CHUNK_OFFSET: UInt = 10098u
    val IMAGE_CHUNK_DATA: UInt = 10099u
    val IMAGE_COMPLETE: UInt = 10100u
    val IMAGE_ID: UInt = 10101u
    val MAP_WIDGET: UInt = 10102u
    val MAP_WIDGET_IMAGE_ID: UInt = 10103u
    val MAP_WIDGET_USER_LOCATION: UInt = 10104u
    val CLARIFY_WIDGET: UInt = 10105u
    val CLARIFY_QUESTION: UInt = 10106u
    val CLARIFY_CONTEXT: UInt = 10107u
    val CLARIFY_OPTION_COUNT: UInt = 10108u
    val CLARIFY_OPTION_0: UInt = 10109u
    val ASSISTANT_RUNTIME: UInt = 10115u
    val WATCH_PROMPT: UInt = 10121u
    val WATCH_READY: UInt = 10122u
    val ANDROID_COMPANION_READY: UInt = 10123u

    fun nextImageId(): Int = imageIds.getAndIncrement()
}
