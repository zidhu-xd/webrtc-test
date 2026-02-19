package com.chatapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chatapp.data.model.Message
import com.chatapp.data.model.MessageStatus
import com.chatapp.viewmodel.AuthViewModel
import com.chatapp.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onCallClick: (String, String, Boolean) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState(initial = null)
    val isLoading by viewModel.isLoading.collectAsState()
    val remoteTyping by viewModel.remoteTyping.collectAsState()

    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Load conversation data
    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    // Auto-scroll to newest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // TODO: In full app, get participant info from conversation
    val participantName = "Chat"
    val participantId = ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(participantName.take(1).uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(participantName, style = MaterialTheme.typography.titleMedium)
                            if (remoteTyping) {
                                Text("typing...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onCallClick(participantId, participantName, false) }) {
                        Icon(Icons.Default.Call, "Voice call")
                    }
                    IconButton(onClick = { onCallClick(participantId, participantName, true) }) {
                        Icon(Icons.Default.Videocam, "Video call")
                    }
                }
            )
        },
        bottomBar = {
            // Message input bar
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message...") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                viewModel.sendMessage(messageText.trim())
                                messageText = ""
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        enabled = messageText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, "Send")
                    }
                }
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp),
                reverseLayout = true,  // Newest messages at bottom
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val isMine = message.senderId == currentUser?.id ||
                            message.senderId == "self"
                    MessageBubble(message = message, isMine = isMine)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message, isMine: Boolean) {
    val bubbleColor = if (isMine)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isMine)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    val alignment = if (isMine) Alignment.End else Alignment.Start
    val shape = if (isMine)
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    else
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = message.content,
                    color = textColor,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(message.timestamp)),
                        fontSize = 11.sp,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    if (isMine) {
                        Spacer(Modifier.width(4.dp))
                        // Message status indicator
                        when (message.status) {
                            MessageStatus.SENDING -> Icon(Icons.Default.Schedule, null,
                                modifier = Modifier.size(12.dp),
                                tint = textColor.copy(alpha = 0.7f))
                            MessageStatus.SENT -> Icon(Icons.Default.Done, null,
                                modifier = Modifier.size(12.dp),
                                tint = textColor.copy(alpha = 0.7f))
                            MessageStatus.DELIVERED -> Icon(Icons.Default.DoneAll, null,
                                modifier = Modifier.size(12.dp),
                                tint = textColor.copy(alpha = 0.7f))
                            MessageStatus.READ -> Icon(Icons.Default.DoneAll, null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.tertiary)
                            MessageStatus.FAILED -> Icon(Icons.Default.ErrorOutline, null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
