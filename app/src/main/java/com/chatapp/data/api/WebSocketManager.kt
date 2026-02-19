package com.chatapp.data.api

import android.util.Log
import com.chatapp.data.model.Message
import com.chatapp.data.model.SignalMessage
import com.chatapp.data.model.SignalType
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket Manager
 * Handles two concerns over a single persistent WebSocket connection:
 *   1. Real-time chat messages (new message events from server)
 *   2. WebRTC signaling (offer/answer/ICE exchange for calls)
 *
 * Uses OkHttp's free WebSocket implementation (Apache 2.0 license).
 * Includes auto-reconnect with exponential backoff.
 */
@Singleton
class WebSocketManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private val TAG = "WebSocketManager"
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    // ==================== EVENT FLOWS ====================
    // These SharedFlows broadcast events to all subscribers (ViewModels, services).

    /** Emits new incoming chat messages */
    private val _messageFlow = MutableSharedFlow<Message>(replay = 0)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()

    /** Emits WebRTC signaling messages (offer/answer/ICE) */
    private val _signalFlow = MutableSharedFlow<SignalMessage>(replay = 0)
    val signalFlow: SharedFlow<SignalMessage> = _signalFlow.asSharedFlow()

    /** Emits user online status changes: Pair(userId, isOnline) */
    private val _userStatusFlow = MutableSharedFlow<Pair<String, Boolean>>(replay = 0)
    val userStatusFlow: SharedFlow<Pair<String, Boolean>> = _userStatusFlow.asSharedFlow()

    /** Emits connection state changes */
    private val _connectionFlow = MutableSharedFlow<Boolean>(replay = 1)
    val connectionFlow: SharedFlow<Boolean> = _connectionFlow.asSharedFlow()

    /**
     * Connect to WebSocket server.
     * @param serverUrl WebSocket URL (e.g., "ws://10.0.2.2:3000")
     * @param token JWT token for authentication (sent as query param)
     */
    fun connect(serverUrl: String, token: String) {
        val url = "$serverUrl?token=$token"
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                reconnectAttempts = 0
                _connectionFlow.tryEmit(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS received: $text")
                handleIncomingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                _connectionFlow.tryEmit(false)
                scheduleReconnect(serverUrl, token)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                _connectionFlow.tryEmit(false)
            }
        })
    }

    /**
     * Parse incoming WebSocket message and route to the correct flow.
     * Server sends JSON with a "type" field to distinguish event types.
     */
    private fun handleIncomingMessage(text: String) {
        try {
            val json = gson.fromJson(text, Map::class.java)
            when (val type = json["type"] as? String) {

                // New chat message from another user
                "new_message" -> {
                    val message = gson.fromJson(
                        gson.toJson(json["data"]),
                        Message::class.java
                    )
                    _messageFlow.tryEmit(message)
                }

                // WebRTC signaling events
                "call_offer", "call_answer", "ice_candidate",
                "call_reject", "call_end", "call_busy" -> {
                    val signal = SignalMessage(
                        type = SignalType.valueOf(type.uppercase()),
                        from = json["from"] as? String ?: "",
                        to = json["to"] as? String ?: "",
                        payload = gson.toJson(json["payload"])
                    )
                    _signalFlow.tryEmit(signal)
                }

                // User online/offline status
                "user_status" -> {
                    val userId = json["userId"] as? String ?: return
                    val online = json["online"] as? Boolean ?: false
                    _userStatusFlow.tryEmit(Pair(userId, online))
                }

                else -> Log.w(TAG, "Unknown WS event type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WS message: ${e.message}")
        }
    }

    /**
     * Send a WebRTC signaling message to a specific user.
     * The server relays this to the target user via their WebSocket connection.
     */
    fun sendSignal(signal: SignalMessage) {
        val payload = mapOf(
            "type" to signal.type.name.lowercase(),
            "from" to signal.from,
            "to" to signal.to,
            "payload" to gson.fromJson(signal.payload, Any::class.java)
        )
        send(gson.toJson(payload))
    }

    /**
     * Send a typing indicator to a conversation.
     */
    fun sendTyping(conversationId: String, userId: String, isTyping: Boolean) {
        val payload = mapOf(
            "type" to "typing",
            "conversationId" to conversationId,
            "userId" to userId,
            "isTyping" to isTyping
        )
        send(gson.toJson(payload))
    }

    private fun send(message: String): Boolean {
        return if (isConnected && webSocket != null) {
            webSocket!!.send(message)
        } else {
            Log.w(TAG, "Cannot send: WebSocket not connected")
            false
        }
    }

    /**
     * Exponential backoff reconnect: waits 1s, 2s, 4s, 8s, 16s before giving up.
     */
    private fun scheduleReconnect(serverUrl: String, token: String) {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts reached. Giving up.")
            return
        }
        val delay = (Math.pow(2.0, reconnectAttempts.toDouble()) * 1000).toLong()
        reconnectAttempts++
        Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            connect(serverUrl, token)
        }, delay)
    }

    fun disconnect() {
        webSocket?.close(1000, "User logged out")
        webSocket = null
        isConnected = false
    }

    fun isConnected() = isConnected
}
