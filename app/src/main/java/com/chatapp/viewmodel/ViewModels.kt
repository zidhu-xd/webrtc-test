package com.chatapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatapp.BuildConfig
import com.chatapp.data.api.WebSocketManager
import com.chatapp.data.model.*
import com.chatapp.data.repository.AuthRepository
import com.chatapp.data.repository.ChatRepository
import com.chatapp.service.WebRtcManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ==================== AUTH VIEW MODEL ====================

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState

    val currentUser = authRepository.getCurrentUser()
    val authToken = authRepository.getAuthToken()

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authRepository.login(username, password)
            if (result.isSuccess) {
                val data = result.getOrNull()!!
                // Connect WebSocket after login
                webSocketManager.connect(BuildConfig.WS_URL, data.token)
                _uiState.value = AuthUiState.Success(data.user)
            } else {
                _uiState.value = AuthUiState.Error(result.exceptionOrNull()?.message ?: "Login failed")
            }
        }
    }

    fun register(username: String, password: String, displayName: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authRepository.register(username, password, displayName)
            if (result.isSuccess) {
                val data = result.getOrNull()!!
                webSocketManager.connect(BuildConfig.WS_URL, data.token)
                _uiState.value = AuthUiState.Success(data.user)
            } else {
                _uiState.value = AuthUiState.Error(result.exceptionOrNull()?.message ?: "Registration failed")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            webSocketManager.disconnect()
            authRepository.logout()
            _uiState.value = AuthUiState.Idle
        }
    }
}

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val user: User) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

// ==================== CONVERSATIONS VIEW MODEL ====================

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users

    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults: StateFlow<List<User>> = _searchResults

    init {
        observeConversations()
        observeUserStatus()
        observeIncomingMessages()
    }

    private fun observeConversations() {
        viewModelScope.launch {
            authRepository.getAuthToken().filterNotNull().collectLatest { token ->
                chatRepository.getConversations(token).collect { conversations ->
                    _conversations.value = conversations
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            authRepository.getAuthToken().firstOrNull()?.let { token ->
                chatRepository.refreshConversations(token)
            }
        }
    }

    fun searchUsers(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                _searchResults.value = emptyList()
                return@launch
            }
            authRepository.getAuthToken().firstOrNull()?.let { token ->
                _searchResults.value = chatRepository.searchUsers(token, query)
            }
        }
    }

    suspend fun getOrCreateConversation(participantId: String): Conversation? {
        val token = authRepository.getAuthToken().firstOrNull() ?: return null
        return chatRepository.getOrCreateConversation(token, participantId)
    }

    private fun observeUserStatus() {
        viewModelScope.launch {
            webSocketManager.userStatusFlow.collect { (userId, online) ->
                chatRepository.updateUserOnlineStatus(userId, online)
            }
        }
    }

    private fun observeIncomingMessages() {
        viewModelScope.launch {
            chatRepository.observeIncomingMessages().collect { message ->
                // Update conversation last message
                _conversations.value = _conversations.value.map { conv ->
                    if (conv.id == message.conversationId) {
                        conv.copy(
                            lastMessage = message.content,
                            lastMessageTime = message.timestamp,
                            unreadCount = conv.unreadCount + 1
                        )
                    } else conv
                }
            }
        }
    }
}

// ==================== CHAT VIEW MODEL ====================

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _remoteTyping = MutableStateFlow(false)
    val remoteTyping: StateFlow<Boolean> = _remoteTyping

    private var conversationId = ""
    private var currentToken = ""

    fun loadConversation(conversationId: String) {
        this.conversationId = conversationId
        viewModelScope.launch {
            _isLoading.value = true
            authRepository.getAuthToken().firstOrNull()?.let { token ->
                currentToken = token
                // Observe local DB (real-time updates)
                chatRepository.getMessages(conversationId).collect { messages ->
                    _messages.value = messages
                    _isLoading.value = false
                }
            }
        }
        // Fetch from API in parallel
        viewModelScope.launch {
            authRepository.getAuthToken().firstOrNull()?.let { token ->
                chatRepository.fetchMessages(token, conversationId)
            }
        }
        // Mark as read
        viewModelScope.launch {
            authRepository.getAuthToken().firstOrNull()?.let { token ->
                chatRepository.markAsRead(token, conversationId)
            }
        }
        // Observe real-time incoming messages for this conversation
        viewModelScope.launch {
            chatRepository.observeIncomingMessages()
                .filter { it.conversationId == conversationId }
                .collect { message ->
                    // Already handled by Room Flow, but mark as read
                    chatRepository.markAsRead(currentToken, conversationId)
                }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || conversationId.isEmpty()) return
        viewModelScope.launch {
            chatRepository.sendMessage(currentToken, conversationId, content)
        }
    }

    fun sendTypingIndicator(userId: String, isTyping: Boolean) {
        webSocketManager.sendTyping(conversationId, userId, isTyping)
    }

    fun observeTyping(remoteUserId: String) {
        // In a full implementation, parse typing events from WS and update _remoteTyping
    }
}

// ==================== CALL VIEW MODEL ====================

@HiltViewModel
class CallViewModel @Inject constructor(
    private val webRtcManager: WebRtcManager,
    private val webSocketManager: WebSocketManager,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _callState = MutableStateFlow(CallState())
    val callState: StateFlow<CallState> = _callState

    val isMuted = webRtcManager.isMuted
    val isCameraOff = webRtcManager.isCameraOff

    init {
        observeSignals()
    }

    /**
     * Initiate an outgoing call.
     */
    fun callUser(targetUserId: String, targetName: String, isVideoCall: Boolean) {
        viewModelScope.launch {
            _callState.value = CallState(
                remoteUserId = targetUserId,
                remoteUserName = targetName,
                isIncoming = false,
                isVideoCall = isVideoCall,
                status = CallStatus.CALLING
            )
            webRtcManager.startCall(targetUserId, isVideoCall)
        }
    }

    /**
     * Accept an incoming call.
     */
    fun acceptCall(sdpOffer: String) {
        viewModelScope.launch {
            val state = _callState.value
            webRtcManager.acceptCall(state.remoteUserId, sdpOffer, state.isVideoCall)
            _callState.value = state.copy(status = CallStatus.CONNECTED)
        }
    }

    /**
     * Reject or end the call.
     */
    fun endCall() {
        val state = _callState.value
        if (state.status == CallStatus.RINGING) {
            webRtcManager.rejectCall(state.remoteUserId)
        } else {
            webRtcManager.endCall()
        }
        _callState.value = CallState(status = CallStatus.ENDED)
    }

    fun toggleMute() = webRtcManager.toggleMute()
    fun toggleCamera() = webRtcManager.toggleCamera()
    fun switchCamera() = webRtcManager.switchCamera()

    fun getLocalVideoTrack() = webRtcManager.getLocalVideoTrack()
    fun getRemoteVideoTrack() = webRtcManager.getRemoteVideoTrack()

    /**
     * Observe WebRTC signaling messages.
     * Routes them to the appropriate WebRTC action.
     */
    private fun observeSignals() {
        viewModelScope.launch {
            webSocketManager.signalFlow.collect { signal ->
                when (signal.type) {
                    SignalType.CALL_OFFER -> {
                        // Incoming call - update state, show incoming call UI
                        _callState.value = CallState(
                            remoteUserId = signal.from,
                            remoteUserName = signal.from, // In full app, resolve name from contacts
                            isIncoming = true,
                            isVideoCall = true, // Parse from payload
                            status = CallStatus.RINGING
                        )
                        // Store the offer SDP so we can answer
                        _pendingOffer = signal.payload
                    }

                    SignalType.CALL_ANSWER -> {
                        _callState.value = _callState.value.copy(status = CallStatus.CONNECTED)
                        // WebRtcManager handles setting remote description
                    }

                    SignalType.CALL_REJECT -> {
                        _callState.value = CallState(status = CallStatus.ENDED)
                    }

                    SignalType.CALL_END -> {
                        webRtcManager.endCall()
                        _callState.value = CallState(status = CallStatus.ENDED)
                    }

                    else -> {}
                }
            }
        }
    }

    private var _pendingOffer: String = ""
    fun getPendingOffer() = _pendingOffer
}
