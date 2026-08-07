package com.my.kizzy.gateway

import com.my.kizzy.gateway.entities.Heartbeat
import com.my.kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import com.my.kizzy.gateway.entities.Payload
import com.my.kizzy.gateway.entities.Ready
import com.my.kizzy.gateway.entities.Resume
import com.my.kizzy.gateway.entities.op.OpCode
import com.my.kizzy.gateway.entities.op.OpCode.DISPATCH
import com.my.kizzy.gateway.entities.op.OpCode.HEARTBEAT
import com.my.kizzy.gateway.entities.op.OpCode.HELLO
import com.my.kizzy.gateway.entities.op.OpCode.IDENTIFY
import com.my.kizzy.gateway.entities.op.OpCode.INVALID_SESSION
import com.my.kizzy.gateway.entities.op.OpCode.PRESENCE_UPDATE
import com.my.kizzy.gateway.entities.op.OpCode.RECONNECT
import com.my.kizzy.gateway.entities.op.OpCode.RESUME
import com.my.kizzy.gateway.entities.presence.Presence
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.logging.Level
import java.util.logging.Level.INFO
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
/**
 * Modified by Zion Huang
 */
open class DiscordWebSocket(
    private val token: String,
) : CoroutineScope {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private var connected = false

    /**
     * Last thing that happened to this socket, in words. Kizzy reports everything through
     * java.util.logging, which is invisible on a release build with no logcat attached - so a
     * failure to connect was indistinguishable from a presence that simply never got sent.
     */
    @Volatile
    var lastStatus: String = "Not connected"
        private set
    private var client: HttpClient = HttpClient {
        install(WebSockets)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    // Built once. This used to be a get(), which handed out a brand new SupervisorJob on every
    // access - so the job that close() cancelled was never the job the connection was launched
    // under, and cancelling it reached nothing. Connections and their heartbeats leaked, and a
    // stale socket could keep answering isRpcRunning() after a logout.
    private val socketJob = SupervisorJob()
    override val coroutineContext: CoroutineContext = socketJob + Dispatchers.Default
    suspend fun connect() {
        launch {
            try {
                Logger.getLogger("Kizzy").log(INFO, "Gateway: Connect called")
                lastStatus = "Connecting…"
                val url = resumeGatewayUrl ?: gatewayUrl
                websocket = client.webSocketSession(url)
                // start receiving messages
                websocket!!.incoming.receiveAsFlow()
                    .collect {
                        when (it) {
                            is Frame.Text -> {
                                val jsonString = it.readText()
                                onMessage(json.decodeFromString(jsonString))
                            }
                            else -> {}
                        }
                    }
                handleClose()
            } catch (e: Exception) {
                Logger.getLogger("Kizzy").log(INFO, "Gateway: ${e.message}")
                lastStatus = "Connection error: ${e.message ?: e::class.simpleName}"
                close()
            }
        }
    }
    private suspend fun handleClose() {
        heartbeatJob?.cancel()
        connected = false
        val close = websocket?.closeReason?.await()
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Closed with code: ${close?.code}, reason: ${close?.message},  can_reconnect: ${close?.code?.toInt() == 4000}")
        // 4004 is the one worth calling out by name: Discord rejected the token.
        lastStatus = when (close?.code?.toInt()) {
            null -> "Disconnected"
            4004 -> "Discord rejected the token (4004). Paste a fresh one."
            else -> "Disconnected (code ${close.code}${close.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""})"
        }
        if (close?.code?.toInt() == 4000) {
            delay(200.milliseconds)
            connect()
        } else
            close()
    }
    private suspend fun onMessage(payload: Payload) {
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch()
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> payload.handleHello()
            else -> {}
        }
    }
    open fun Payload.handleDispatch() {
        when (this.t.toString()) {
            "READY" -> {
                val ready = json.decodeFromJsonElement<Ready>(this.d!!)
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl + "/?v=10&encoding=json"
                Logger.getLogger("Kizzy").log(INFO, "Gateway: resume_gateway_url updated to $resumeGatewayUrl")
                Logger.getLogger("Kizzy").log(INFO, "Gateway: session_id updated to $sessionId")
                connected = true
                lastStatus = "Connected"
                return
            }
            "RESUMED" -> {
                Logger.getLogger("Kizzy").log(INFO, "Gateway: Session Resumed")
            }
            else -> {}
        }
    }
    private suspend inline fun handleInvalidSession() {
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Handling Invalid Session")
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Sending Identify after 150ms")
        delay(150)
        sendIdentify()
    }
    private suspend inline fun Payload.handleHello() {
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        heartbeatInterval = json.decodeFromJsonElement<Heartbeat>(this.d!!).heartbeatInterval
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Setting heartbeatInterval= $heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
    }
    private suspend fun sendHeartBeat() {
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Sending $HEARTBEAT with seq: $sequence")
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) "null" else sequence.toString(),
        )
    }
    private suspend inline fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect"
            )
        )
    }
    private suspend fun sendIdentify() {
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Sending $IDENTIFY")
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload()
        )
    }
    private suspend fun sendResume() {
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token
            )
        )
    }
    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = launch {
            while (isActive) {
                sendHeartBeat()
                delay(interval)
            }
        }
    }
    /** Waits up to [timeoutMs] for the gateway to finish identifying. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (isSocketConnectedToAccount()) return true
            delay(POLL_MS.milliseconds)
            waited += POLL_MS
        }
        return isSocketConnectedToAccount()
    }

    private fun isSocketConnectedToAccount(): Boolean {
        return connected && websocket?.isActive == true
    }
    @OptIn(DelicateCoroutinesApi::class)
    fun isWebSocketConnected(): Boolean {
        return websocket?.incoming != null && websocket?.outgoing?.isClosedForSend == false
    }
    private suspend inline fun <reified T> send(op: OpCode, d: T?) {
        if (websocket?.isActive == true) {
            val payload = json.encodeToString(
                Payload(
                    op = op,
                    d = json.encodeToJsonElement(d),
                )
            )
            websocket?.send(Frame.Text(payload))
        }
    }
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 50L
    }

    fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        // cancelChildren, not cancel: cancelling the scope's own job would leave it permanently
        // dead, and a later connect() would launch into a cancelled scope and never run - so
        // sendActivity would then spin forever waiting for a connection that cannot happen.
        socketJob.cancelChildren()
        resumeGatewayUrl = null
        sessionId = null
        connected = false
        runBlocking {
            websocket?.close()
            Logger.getLogger("Kizzy").log(Level.SEVERE, "Gateway: Connection to gateway closed")
        }
    }
    suspend fun sendActivity(presence: Presence) {
        // Bounded. This used to loop forever waiting for the socket to identify, so a connection
        // that never succeeded left the caller suspended for the life of the process with nothing
        // logged and nothing shown.
        if (!awaitConnected(CONNECT_TIMEOUT_MS)) {
            Logger.getLogger("Kizzy").log(INFO, "Gateway: gave up waiting to connect")
            if (lastStatus == "Connecting…" || lastStatus == "Not connected") {
                lastStatus = "Timed out connecting to Discord"
            }
            return
        }
        Logger.getLogger("Kizzy").log(INFO, "Gateway: Sending $PRESENCE_UPDATE")
        send(
            op = PRESENCE_UPDATE,
            d = presence
        )
    }
}