package com.chatapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// ==================== AUTH MODELS ====================

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String
)

data class AuthResponse(
    val token: String,
    val user: User
)

// ==================== USER MODEL ====================

data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatar: String? = null,
    val online: Boolean = false,
    val lastSeen: Long = 0
)

// ==================== MESSAGE MODELS ====================

/**
 * Message entity stored in local Room database for offline access.
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val mediaUrl: String? = null
)

enum class MessageType {
    TEXT, IMAGE, FILE, CALL_LOG
}

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED
}

// ==================== CONVERSATION MODEL ====================

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val participantId: String,
    val participantName: String,
    val participantAvatar: String? = null,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0,
    val unreadCount: Int = 0,
    val isOnline: Boolean = false
)

// ==================== API RESPONSE WRAPPERS ====================

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null
)

data class MessagesResponse(
    val messages: List<Message>,
    val hasMore: Boolean,
    val page: Int
)

data class UsersResponse(
    val users: List<User>
)

// ==================== WEBRTC SIGNALING MODELS ====================

/**
 * WebRTC signaling messages sent via WebSocket for call setup.
 * These follow the standard WebRTC offer/answer/ICE candidate flow.
 */
data class SignalMessage(
    val type: SignalType,
    val from: String,
    val to: String,
    val payload: String  // JSON payload (SDP offer/answer or ICE candidate)
)

enum class SignalType {
    CALL_OFFER,        // Initiator sends SDP offer
    CALL_ANSWER,       // Receiver sends SDP answer
    ICE_CANDIDATE,     // Both sides exchange ICE candidates
    CALL_REJECT,       // Receiver rejects the call
    CALL_END,          // Either party ends the call
    CALL_BUSY,         // Receiver is in another call
    USER_JOINED,       // User connected to WebSocket
    USER_LEFT          // User disconnected
}

// ==================== WEBSOCKET EVENT MODELS ====================

data class WebSocketEvent(
    val type: String,  // "message", "signal", "user_status", etc.
    val data: Any?
)

// ==================== CALL MODELS ====================

data class CallState(
    val callId: String = "",
    val remoteUserId: String = "",
    val remoteUserName: String = "",
    val isIncoming: Boolean = false,
    val isVideoCall: Boolean = false,
    val status: CallStatus = CallStatus.IDLE,
    val duration: Long = 0
)

enum class CallStatus {
    IDLE,       // No active call
    CALLING,    // Outgoing call, waiting for answer
    RINGING,    // Incoming call
    CONNECTED,  // Call in progress
    ENDED       // Call finished
}
