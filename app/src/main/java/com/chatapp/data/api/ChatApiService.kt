package com.chatapp.data.api

import com.chatapp.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * REST API interface using Retrofit.
 * All endpoints match the Node.js backend server.
 *
 * Base URL is set in build.gradle via buildConfigField.
 * Default: http://10.0.2.2:3000/ (localhost from Android emulator)
 */
interface ChatApiService {

    // ==================== AUTH ENDPOINTS ====================

    /**
     * Register a new user account.
     * POST /api/auth/register
     */
    @POST("api/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<ApiResponse<AuthResponse>>

    /**
     * Login with username + password.
     * POST /api/auth/login
     * Returns JWT token for subsequent requests.
     */
    @POST("api/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<ApiResponse<AuthResponse>>

    /**
     * Get current user profile.
     * GET /api/auth/me
     * Requires: Authorization: Bearer <token>
     */
    @GET("api/auth/me")
    suspend fun getProfile(
        @Header("Authorization") token: String
    ): Response<ApiResponse<User>>

    // ==================== USER ENDPOINTS ====================

    /**
     * Search for users by username.
     * GET /api/users/search?q=<query>
     */
    @GET("api/users/search")
    suspend fun searchUsers(
        @Header("Authorization") token: String,
        @Query("q") query: String
    ): Response<ApiResponse<UsersResponse>>

    /**
     * Get all users (contacts list).
     * GET /api/users
     */
    @GET("api/users")
    suspend fun getUsers(
        @Header("Authorization") token: String
    ): Response<ApiResponse<UsersResponse>>

    // ==================== MESSAGE ENDPOINTS ====================

    /**
     * Get messages for a conversation.
     * GET /api/messages/{conversationId}?page=1&limit=50
     * Messages are paginated, newest first.
     */
    @GET("api/messages/{conversationId}")
    suspend fun getMessages(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<MessagesResponse>>

    /**
     * Send a new message.
     * POST /api/messages
     */
    @POST("api/messages")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @Body message: SendMessageRequest
    ): Response<ApiResponse<Message>>

    /**
     * Mark messages in a conversation as read.
     * PUT /api/messages/{conversationId}/read
     */
    @PUT("api/messages/{conversationId}/read")
    suspend fun markAsRead(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String
    ): Response<ApiResponse<Unit>>

    // ==================== CONVERSATION ENDPOINTS ====================

    /**
     * Get all conversations for the current user.
     * GET /api/conversations
     */
    @GET("api/conversations")
    suspend fun getConversations(
        @Header("Authorization") token: String
    ): Response<ApiResponse<List<Conversation>>>

    /**
     * Get or create a conversation with a specific user.
     * POST /api/conversations
     */
    @POST("api/conversations")
    suspend fun getOrCreateConversation(
        @Header("Authorization") token: String,
        @Body request: CreateConversationRequest
    ): Response<ApiResponse<Conversation>>
}

// Additional request models
data class SendMessageRequest(
    val conversationId: String,
    val content: String,
    val type: String = "TEXT"
)

data class CreateConversationRequest(
    val participantId: String
)
