package com.chatapp.data.repository

import com.chatapp.data.api.*
import com.chatapp.data.model.*
import com.chatapp.utils.UserPreferences
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat Repository
 * Single source of truth for all data.
 * Handles the sync between local Room DB (offline cache) and remote API.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val api: ChatApiService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val webSocketManager: WebSocketManager,
    private val userPrefs: UserPreferences
) {
    // ==================== CONVERSATIONS ====================

    /**
     * Get conversations from local DB (always fresh via Flow),
     * and fetch from API to keep in sync.
     */
    fun getConversations(token: String): Flow<List<Conversation>> {
        return conversationDao.getConversations()
    }

    suspend fun refreshConversations(token: String) {
        val response = api.getConversations(userPrefs.bearerToken(token))
        if (response.isSuccessful) {
            response.body()?.data?.let { conversations ->
                conversationDao.insertConversations(conversations)
            }
        }
    }

    suspend fun getOrCreateConversation(token: String, participantId: String): Conversation? {
        val response = api.getOrCreateConversation(
            userPrefs.bearerToken(token),
            CreateConversationRequest(participantId)
        )
        return if (response.isSuccessful) {
            response.body()?.data?.also { conversation ->
                conversationDao.insertConversation(conversation)
            }
        } else null
    }

    // ==================== MESSAGES ====================

    /**
     * Get messages from local DB as a Flow (real-time updates).
     * New messages from WebSocket are inserted into DB and automatically
     * emitted here.
     */
    fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessages(conversationId)
    }

    /**
     * Fetch messages from API and cache in local DB.
     */
    suspend fun fetchMessages(token: String, conversationId: String, page: Int = 1) {
        val response = api.getMessages(userPrefs.bearerToken(token), conversationId, page)
        if (response.isSuccessful) {
            response.body()?.data?.messages?.let { messages ->
                messageDao.insertMessages(messages)
            }
        }
    }

    /**
     * Send a message via REST API.
     * Optimistic update: insert locally first, then confirm with server.
     */
    suspend fun sendMessage(token: String, conversationId: String, content: String): Result<Message> {
        // Optimistic insert with SENDING status
        val tempMessage = Message(
            id = "temp_${System.currentTimeMillis()}",
            conversationId = conversationId,
            senderId = "self",
            senderName = "You",
            content = content,
            status = MessageStatus.SENDING,
            timestamp = System.currentTimeMillis()
        )
        messageDao.insertMessage(tempMessage)

        return try {
            val response = api.sendMessage(
                userPrefs.bearerToken(token),
                SendMessageRequest(conversationId, content)
            )
            if (response.isSuccessful && response.body()?.data != null) {
                val confirmedMessage = response.body()!!.data!!
                messageDao.insertMessage(confirmedMessage) // Replace temp with real
                conversationDao.updateLastMessage(conversationId, content, confirmedMessage.timestamp)
                Result.success(confirmedMessage)
            } else {
                messageDao.updateStatus(tempMessage.id, MessageStatus.FAILED.name)
                Result.failure(Exception("Failed to send message"))
            }
        } catch (e: Exception) {
            messageDao.updateStatus(tempMessage.id, MessageStatus.FAILED.name)
            Result.failure(e)
        }
    }

    suspend fun markAsRead(token: String, conversationId: String) {
        api.markAsRead(userPrefs.bearerToken(token), conversationId)
        conversationDao.clearUnread(conversationId)
    }

    // ==================== USERS ====================

    suspend fun searchUsers(token: String, query: String): List<User> {
        val response = api.searchUsers(userPrefs.bearerToken(token), query)
        return response.body()?.data?.users ?: emptyList()
    }

    suspend fun getUsers(token: String): List<User> {
        val response = api.getUsers(userPrefs.bearerToken(token))
        return response.body()?.data?.users ?: emptyList()
    }

    // ==================== WEBSOCKET REAL-TIME ====================

    /**
     * Listen for real-time messages and insert into DB.
     * Call this once after connecting to WebSocket.
     */
    fun observeIncomingMessages() = webSocketManager.messageFlow

    fun observeUserStatus() = webSocketManager.userStatusFlow

    suspend fun updateUserOnlineStatus(userId: String, online: Boolean) {
        conversationDao.updateUserOnline(userId, online)
    }
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: ChatApiService,
    private val userPrefs: UserPreferences
) {
    suspend fun login(username: String, password: String): Result<AuthResponse> {
        return try {
            val response = api.login(LoginRequest(username, password))
            if (response.isSuccessful && response.body()?.data != null) {
                val data = response.body()!!.data!!
                userPrefs.saveAuthData(data.token, data.user)
                Result.success(data)
            } else {
                Result.failure(Exception(response.body()?.message ?: "Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(username: String, password: String, displayName: String): Result<AuthResponse> {
        return try {
            val response = api.register(RegisterRequest(username, password, displayName))
            if (response.isSuccessful && response.body()?.data != null) {
                val data = response.body()!!.data!!
                userPrefs.saveAuthData(data.token, data.user)
                Result.success(data)
            } else {
                Result.failure(Exception(response.body()?.message ?: "Registration failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        userPrefs.clearAuthData()
    }

    fun getAuthToken() = userPrefs.authToken
    fun getCurrentUser() = userPrefs.currentUser
}
