package com.adgent.trader.core.provider

import com.adgent.trader.core.model.PriceTick
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * WebSocket di un provider di dati live. Incapsula la meccanica comune a tutti
 * gli exchange (riconnessione con backoff esponenziale + jitter, ping keepalive
 * via [OkHttpClient], riconnessione su chiusura remota): ogni adapter implementa
 * solo come costruire la richiesta, i messaggi di subscribe e come parsare i
 * frame. Un solo OkHttp condiviso per tutti i provider (connection pooling).
 *
 * I simboli gestiti qui sono CANONICI (`compact`, es. "BTCUSDT"): l'adapter li
 * traduce in formato provider alla frontiera della WS e riporta in canonico i
 * tick. Un frame che produce tick non richiesti viene scartato dal filtro
 * [wanted] (es. stream globali tipo Binance !miniTicker).
 */
abstract class ProviderWebSocket(
    protected val baseUrl: String,
    protected val client: OkHttpClient,
) {
    /** URL di connessione (default = [baseUrl]; es. Binance aggiunge ?streams=). */
    protected open fun buildRequest(): Request = Request.Builder().url(baseUrl).build()

    /** Messaggio di subscribe per un batch di simboli canonici, o null se la WS
     *  non richiede subscribe wire (stream globale, filtro client-side). */
    protected open fun subscribeMessage(symbols: Set<String>): String? = null

    /** Messaggio di unsubscribe per un batch di simboli canonici, o null. */
    protected open fun unsubscribeMessage(symbols: Set<String>): String? = null

    /** Varianti multi-messaggio: la maggior parte degli exchange accetta un solo
     *  batch; Bitfinex vuole UN messaggio per simbolo. Default = delega a quella
     *  singola (lista di 0/1 messaggi). */
    protected open fun subscribeMessages(symbols: Set<String>): List<String> =
        subscribeMessage(symbols)?.let { listOf(it) } ?: emptyList()

    protected open fun unsubscribeMessages(symbols: Set<String>): List<String> =
        unsubscribeMessage(symbols)?.let { listOf(it) } ?: emptyList()

    /** Heartbeat applicativo richiesto dall'exchange (0 = disabilitato):
     *  es. Bybit vuole {"op":"ping"} ogni 20s, Kraken {"event":"ping"} ogni ~30s.
     *  I ping di livello protocollo di OkHttp (pingInterval) non sempre bastano. */
    protected open fun keepAliveIntervalMs(): Long = 0L
    protected open fun keepAliveMessage(): String = ""

    /** Risposta a un frame di controllo del server (ping/welcome/ack), o null
     *  per ignorarlo: es. OKX e KuCoin mandano "ping" e vogliono il "pong". */
    protected open fun controlReply(text: String): String? = null

    /** Traduce un frame nel formato provider in tick CANONICI. */
    protected abstract fun parseFrame(text: String): List<PriceTick>

    private val _ticks = MutableSharedFlow<List<PriceTick>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ticks: Flow<List<PriceTick>> = _ticks.asSharedFlow()

    private val _state = MutableStateFlow(WsState.OFF)
    val state: Flow<WsState> = _state.asStateFlow()

    /** Simboli canonici richiesti (filtro client-side). */
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var shouldRun = false
    @Volatile private var attempt = 0
    @Volatile private var keepAliveThread: Thread? = null

    fun connect() {
        if (shouldRun) return
        shouldRun = true
        open()
    }

    fun disconnect() {
        shouldRun = false
        stopKeepAlive()
        socket?.close(1000, "client shutdown")
        socket = null
        _state.value = WsState.OFF
    }

    fun subscribe(symbols: Collection<String>) {
        val added = symbols.filter { wanted.add(it) }
        if (added.isNotEmpty()) wire(added.toSet(), ::subscribeMessages)
    }

    fun unsubscribe(symbols: Collection<String>) {
        val removed = symbols.filter { wanted.remove(it) }
        if (removed.isNotEmpty()) wire(removed.toSet(), ::unsubscribeMessages)
    }

    private fun wire(syms: Set<String>, build: (Set<String>) -> List<String>) {
        val s = socket ?: return
        if (_state.value != WsState.LIVE) return
        build(syms).forEach { runCatching { s.send(it) } }
    }

    private fun open() {
        if (!shouldRun) return
        _state.value = WsState.CONNECTING
        socket = client.newWebSocket(buildRequest(), Listener())
        startKeepAlive()
    }

    private fun startKeepAlive() {
        val interval = keepAliveIntervalMs()
        if (interval <= 0) return
        stopKeepAlive()
        val t = Thread {
            while (shouldRun) {
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val s = socket
                if (s != null && _state.value == WsState.LIVE) {
                    runCatching { s.send(keepAliveMessage()) }
                }
            }
        }
        keepAliveThread = t
        t.start()
    }

    private fun stopKeepAlive() {
        keepAliveThread?.interrupt()
        keepAliveThread = null
    }

    private fun reconnectDelay(): Long =
        (1000L shl attempt.coerceAtMost(5)) + Random.nextLong(250)

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _state.value = WsState.LIVE
            wire(wanted.toSet(), ::subscribeMessages)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val reply = runCatching { controlReply(text) }.getOrNull()
            if (reply != null) {
                runCatching { webSocket.send(reply) }
                return
            }
            runCatching { parseFrame(text) }.getOrNull()?.let { batch ->
                val kept = batch.filter { it.symbol in wanted }
                if (kept.isNotEmpty()) _ticks.tryEmit(kept)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (shouldRun) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        _state.value = WsState.RECONNECTING
        val delayMs = reconnectDelay()
        attempt++
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
            }
            open()
        }.start()
    }

    enum class WsState { OFF, CONNECTING, LIVE, RECONNECTING }
}
