package com.adgent.trader.core.network

import com.adgent.trader.core.model.PriceTick
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Client WebSocket per lo stream pubblico combinato Binance.
 * Default: `!miniTicker@arr` → tick di tutti i simboli che cambiano (1/s max).
 *
 * Gestisce: riconnessione con backoff esponenziale + jitter, chiusura forzata
 * 24h di Binance, buffer dei tick quando nessuno ascolta.
 */
class BinanceWebSocket(
    private val streams: String = "!miniTicker@arr",
    private val baseUrl: String = DEFAULT_WS_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _ticks = MutableSharedFlow<List<PriceTick>>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Batch di tick arrivati nello stesso frame. */
    val ticks: Flow<List<PriceTick>> = _ticks.asSharedFlow()

    private val _state = MutableStateFlow(WsState.OFF)
    val state: Flow<WsState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var shouldRun = false
    @Volatile private var attempt = 0

    fun connect() {
        if (shouldRun) return
        shouldRun = true
        open()
    }

    fun disconnect() {
        shouldRun = false
        socket?.close(1000, "client shutdown")
        socket = null
        _state.value = WsState.OFF
    }

    private fun open() {
        if (!shouldRun) return
        _state.value = WsState.CONNECTING
        val request = Request.Builder()
            .url("$baseUrl/stream?streams=$streams")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun reconnectDelay(): Long =
        (1000L shl attempt.coerceAtMost(5)) + Random.nextLong(250)

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _state.value = WsState.LIVE
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { parseFrame(text) }.getOrNull()?.let { batch ->
                if (batch.isNotEmpty()) _ticks.tryEmit(batch)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Binance forza la disconnessione dopo 24h: riconnetti se volevamo restare.
            if (shouldRun) scheduleReconnect()
        }
    }

    private fun parseFrame(text: String): List<PriceTick> {
        val root = json.parseToJsonElement(text).jsonObject
        val payload = root["data"] ?: return emptyList()
        val array = when (payload) {
            is kotlinx.serialization.json.JsonArray -> payload
            else -> listOf(payload) // stream singolo: oggetto unico
        }
        return array.mapNotNull { el ->
            val o = el.jsonObject
            val symbol = o["s"]?.jsonPrimitive?.content ?: return@mapNotNull null
            PriceTick(
                symbol = symbol,
                price = o["c"]!!.jsonPrimitive.content.toDouble(),
                open24h = o["o"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                high24h = o["h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                low24h = o["l"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                quoteVolume24h = o["q"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        _state.value = WsState.RECONNECTING
        val delayMs = reconnectDelay()
        attempt++
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
            open()
        }.start()
    }

    enum class WsState { OFF, CONNECTING, LIVE, RECONNECTING }

    companion object {
        const val DEFAULT_WS_URL = "wss://data-stream.binance.vision"
    }
}
