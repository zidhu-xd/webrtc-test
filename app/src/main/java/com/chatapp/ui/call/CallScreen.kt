package com.chatapp.ui.call

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.chatapp.data.model.CallStatus
import com.chatapp.viewmodel.CallViewModel
import org.webrtc.SurfaceViewRenderer

/**
 * Call Screen
 * Shows video (WebRTC SurfaceViewRenderer) or voice call UI.
 * Handles both incoming and outgoing calls.
 */
@Composable
fun CallScreen(
    remoteUserId: String,
    remoteName: String,
    isVideoCall: Boolean,
    onCallEnd: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val callState by viewModel.callState.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isCameraOff by viewModel.isCameraOff.collectAsState()

    // Initiate outgoing call on entry
    LaunchedEffect(Unit) {
        if (!callState.isIncoming) {
            viewModel.callUser(remoteUserId, remoteName, isVideoCall)
        }
    }

    // Navigate back when call ends
    LaunchedEffect(callState.status) {
        if (callState.status == CallStatus.ENDED) onCallEnd()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        if (isVideoCall && callState.status == CallStatus.CONNECTED) {
            // ==================== VIDEO CALL UI ====================

            // Remote video (full screen)
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).also { renderer ->
                        // Initialize with WebRTC EGL context
                        // renderer.init(eglBase.eglBaseContext, null)
                        viewModel.getRemoteVideoTrack()?.addSink(renderer)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Local video (picture-in-picture, top right)
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).also { renderer ->
                        // renderer.init(eglBase.eglBaseContext, null)
                        renderer.setMirror(true) // Mirror front camera
                        viewModel.getLocalVideoTrack()?.addSink(renderer)
                    }
                },
                modifier = Modifier
                    .size(120.dp, 160.dp)
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

        } else {
            // ==================== VOICE / WAITING CALL UI ====================
            VoiceCallUI(
                callState = callState,
                remoteName = remoteName,
                isVideoCall = isVideoCall
            )
        }

        // ==================== INCOMING CALL OVERLAY ====================
        if (callState.status == CallStatus.RINGING) {
            IncomingCallOverlay(
                callerName = callState.remoteUserName,
                isVideoCall = callState.isVideoCall,
                onAccept = { viewModel.acceptCall(viewModel.getPendingOffer()) },
                onReject = { viewModel.endCall() }
            )
        } else {
            // ==================== CALL CONTROLS ====================
            CallControls(
                modifier = Modifier.align(Alignment.BottomCenter),
                isMuted = isMuted,
                isCameraOff = isCameraOff,
                isVideoCall = isVideoCall,
                callStatus = callState.status,
                onMuteToggle = { viewModel.toggleMute() },
                onCameraToggle = { viewModel.toggleCamera() },
                onSwitchCamera = { viewModel.switchCamera() },
                onEndCall = { viewModel.endCall() }
            )
        }
    }
}

@Composable
private fun VoiceCallUI(
    callState: com.chatapp.data.model.CallState,
    remoteName: String,
    isVideoCall: Boolean
) {
    // Pulsing animation for "calling" state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Avatar with pulse animation during calling
        val avatarScale = if (callState.status == CallStatus.CALLING) scale else 1f
        Box(
            modifier = Modifier
                .scale(avatarScale)
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = remoteName.take(1).uppercase(),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(remoteName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))

        val statusText = when (callState.status) {
            CallStatus.CALLING -> if (isVideoCall) "Starting video call..." else "Calling..."
            CallStatus.CONNECTED -> "Connected"
            CallStatus.RINGING -> "Incoming ${if (isVideoCall) "video" else ""} call"
            CallStatus.ENDED -> "Call ended"
            else -> ""
        }
        Text(statusText, fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun IncomingCallOverlay(
    callerName: String,
    isVideoCall: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Incoming ${if (isVideoCall) "Video" else "Voice"} Call",
                color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(callerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(48.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Reject
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onReject,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.CallEnd, "Reject", modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Decline", color = Color.White)
                }

                // Accept
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onAccept,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Icon(
                            if (isVideoCall) Icons.Default.Videocam else Icons.Default.Call,
                            "Accept", modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Accept", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CallControls(
    modifier: Modifier,
    isMuted: Boolean,
    isCameraOff: Boolean,
    isVideoCall: Boolean,
    callStatus: CallStatus,
    onMuteToggle: () -> Unit,
    onCameraToggle: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndCall: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute button
        CallControlButton(
            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = if (isMuted) "Unmute" else "Mute",
            isActive = isMuted,
            onClick = onMuteToggle
        )

        // End call button (large red)
        FilledIconButton(
            onClick = onEndCall,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.CallEnd, "End call", modifier = Modifier.size(32.dp))
        }

        if (isVideoCall) {
            // Camera toggle
            CallControlButton(
                icon = if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                label = if (isCameraOff) "Camera On" else "Camera Off",
                isActive = isCameraOff,
                onClick = onCameraToggle
            )
        } else {
            // Speaker toggle (placeholder)
            CallControlButton(
                icon = Icons.Default.VolumeUp,
                label = "Speaker",
                isActive = false,
                onClick = {}
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isActive)
                    MaterialTheme.colorScheme.errorContainer
                else
                    Color.White.copy(alpha = 0.2f)
            )
        ) {
            Icon(icon, label, modifier = Modifier.size(24.dp), tint = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
