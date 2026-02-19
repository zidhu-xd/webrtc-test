package com.chatapp.service

import android.content.Context
import android.util.Log
import com.chatapp.data.api.WebSocketManager
import com.chatapp.data.model.SignalMessage
import com.chatapp.data.model.SignalType
import com.google.gson.Gson
import io.getstream.webrtc.android.StreamPeerConnection
import io.getstream.webrtc.android.StreamPeerConnectionFactory
import io.getstream.webrtc.android.StreamPeerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebRTC Manager
 *
 * Handles the full WebRTC call lifecycle:
 *   1. PeerConnection setup with STUN/TURN servers
 *   2. SDP offer/answer exchange via WebSocket signaling
 *   3. ICE candidate exchange
 *   4. Local/remote audio & video tracks
 *
 * Uses Google's open-source WebRTC library via Stream's free wrapper.
 * STUN server: Google's free stun.l.google.com (no account needed).
 *
 * Call Flow:
 *   CALLER:  createOffer() -> send CALL_OFFER -> receive CALL_ANSWER -> exchange ICE
 *   CALLEE:  receive CALL_OFFER -> createAnswer() -> send CALL_ANSWER -> exchange ICE
 */
@Singleton
class WebRtcManager @Inject constructor(
    private val context: Context,
    private val webSocketManager: WebSocketManager,
    private val gson: Gson
) {
    private val TAG = "WebRtcManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Stream WebRTC factory (wraps native WebRTC PeerConnectionFactory)
    private lateinit var peerConnectionFactory: StreamPeerConnectionFactory
    private var peerConnection: StreamPeerConnection? = null

    // Audio/Video tracks
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null

    // Call state
    private val _localVideoSink = MutableStateFlow<VideoSink?>(null)
    val localVideoSink: StateFlow<VideoSink?> = _localVideoSink

    private val _remoteVideoSink = MutableStateFlow<VideoSink?>(null)
    val remoteVideoSink: StateFlow<VideoSink?> = _remoteVideoSink

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isCameraOff = MutableStateFlow(false)
    val isCameraOff: StateFlow<Boolean> = _isCameraOff

    private var localUserId = ""
    private var remoteUserId = ""

    /**
     * STUN/TURN server configuration.
     * Using Google's FREE public STUN server - no account needed.
     * For production, add TURN servers for reliability behind NAT.
     *
     * Free TURN options:
     *   - Metered.ca: 50GB/mo free
     *   - Xirsys: limited free tier
     *   - Self-hosted coturn (open source)
     */
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        // Add TURN servers here for production (needed for users behind symmetric NAT):
        // PeerConnection.IceServer.builder("turn:your-turn-server:3478")
        //     .setUsername("username")
        //     .setPassword("password")
        //     .createIceServer()
    )

    /**
     * Initialize the WebRTC PeerConnectionFactory.
     * Must be called once before starting any calls.
     */
    fun initialize(userId: String) {
        localUserId = userId
        peerConnectionFactory = StreamPeerConnectionFactory(context)

        // Listen for incoming signaling messages
        scope.launch {
            webSocketManager.signalFlow.collect { signal ->
                handleSignal(signal)
            }
        }
    }

    /**
     * Start an outgoing call.
     * Creates local media streams and sends a WebRTC offer to the remote user.
     *
     * @param targetUserId The user to call
     * @param isVideoCall Whether to include video (true) or audio-only (false)
     */
    suspend fun startCall(targetUserId: String, isVideoCall: Boolean) {
        remoteUserId = targetUserId
        setupPeerConnection()
        setupLocalMedia(isVideoCall)
        createAndSendOffer()
    }

    /**
     * Accept an incoming call.
     * Sets up local media and sends a WebRTC answer back to the caller.
     *
     * @param callerId The user who called us
     * @param sdpOffer The SDP offer from the caller (from signaling)
     * @param isVideoCall Whether this is a video call
     */
    suspend fun acceptCall(callerId: String, sdpOffer: String, isVideoCall: Boolean) {
        remoteUserId = callerId
        setupPeerConnection()
        setupLocalMedia(isVideoCall)

        // Set the remote description (caller's offer)
        val sessionDescription = SessionDescription(
            SessionDescription.Type.OFFER,
            sdpOffer
        )
        peerConnection?.setRemoteDescription(sessionDescription)

        // Create and send answer
        createAndSendAnswer()
    }

    /**
     * End the current call.
     * Closes peer connection and releases media resources.
     */
    fun endCall() {
        // Notify the other party
        webSocketManager.sendSignal(
            SignalMessage(
                type = SignalType.CALL_END,
                from = localUserId,
                to = remoteUserId,
                payload = "{}"
            )
        )
        cleanup()
    }

    /**
     * Reject an incoming call.
     */
    fun rejectCall(callerId: String) {
        webSocketManager.sendSignal(
            SignalMessage(
                type = SignalType.CALL_REJECT,
                from = localUserId,
                to = callerId,
                payload = "{}"
            )
        )
    }

    fun toggleMute() {
        localAudioTrack?.setEnabled(_isMuted.value)
        _isMuted.value = !_isMuted.value
    }

    fun toggleCamera() {
        localVideoTrack?.setEnabled(_isCameraOff.value)
        _isCameraOff.value = !_isCameraOff.value
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    // ==================== PRIVATE SETUP METHODS ====================

    private fun setupPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.makePeerConnection(
            coroutineScope = scope,
            configuration = rtcConfig,
            type = StreamPeerType.SUBSCRIBER,
            mediaConstraints = MediaConstraints(),
            onStreamAdded = { stream ->
                // Remote stream received - attach to UI
                stream.videoTracks.firstOrNull()?.let { track ->
                    Log.d(TAG, "Remote video track received")
                    // The sink is set by the UI layer
                }
                Log.d(TAG, "Remote stream added: ${stream.audioTracks.size} audio, ${stream.videoTracks.size} video")
            },
            onNegotiationNeeded = { peerConnection, type ->
                Log.d(TAG, "Negotiation needed")
            },
            onIceCandidateRequest = { candidate ->
                // Send each ICE candidate to the remote peer via WebSocket
                val candidateJson = gson.toJson(mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp
                ))
                webSocketManager.sendSignal(
                    SignalMessage(
                        type = SignalType.ICE_CANDIDATE,
                        from = localUserId,
                        to = remoteUserId,
                        payload = candidateJson
                    )
                )
            }
        )
    }

    private fun setupLocalMedia(isVideoCall: Boolean) {
        val factory = peerConnectionFactory.factory

        // Audio track (always needed)
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("local_audio_track", localAudioSource)
        peerConnection?.connection?.addTrack(localAudioTrack)

        if (isVideoCall) {
            // Video capturer (front camera by default)
            videoCapturer = createCameraCapturer(factory)
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                peerConnectionFactory.eglBase.eglBaseContext
            )
            localVideoSource = factory.createVideoSource(false)
            videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 30)

            localVideoTrack = factory.createVideoTrack("local_video_track", localVideoSource)
            peerConnection?.connection?.addTrack(localVideoTrack)
        }
    }

    private fun createCameraCapturer(factory: PeerConnectionFactory): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        // Try front camera first, fall back to back camera
        return enumerator.deviceNames
            .firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
            ?: enumerator.deviceNames
                .firstOrNull { enumerator.isBackFacing(it) }
                ?.let { enumerator.createCapturer(it, null) }
    }

    private suspend fun createAndSendOffer() {
        val offer = peerConnection?.createOffer()
        offer?.let { sdp ->
            peerConnection?.setLocalDescription(sdp)
            webSocketManager.sendSignal(
                SignalMessage(
                    type = SignalType.CALL_OFFER,
                    from = localUserId,
                    to = remoteUserId,
                    payload = sdp.description
                )
            )
            Log.d(TAG, "Sent call offer to $remoteUserId")
        }
    }

    private suspend fun createAndSendAnswer() {
        val answer = peerConnection?.createAnswer()
        answer?.let { sdp ->
            peerConnection?.setLocalDescription(sdp)
            webSocketManager.sendSignal(
                SignalMessage(
                    type = SignalType.CALL_ANSWER,
                    from = localUserId,
                    to = remoteUserId,
                    payload = sdp.description
                )
            )
            Log.d(TAG, "Sent call answer to $remoteUserId")
        }
    }

    private fun handleSignal(signal: SignalMessage) {
        scope.launch {
            when (signal.type) {
                SignalType.CALL_ANSWER -> {
                    // Received answer to our offer - set as remote description
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, signal.payload)
                    peerConnection?.setRemoteDescription(sdp)
                    Log.d(TAG, "Set remote description (answer)")
                }

                SignalType.ICE_CANDIDATE -> {
                    // Add ICE candidate from remote peer
                    try {
                        val map = gson.fromJson(signal.payload, Map::class.java)
                        val candidate = IceCandidate(
                            map["sdpMid"] as String,
                            (map["sdpMLineIndex"] as Double).toInt(),
                            map["candidate"] as String
                        )
                        peerConnection?.addIceCandidate(candidate)
                        Log.d(TAG, "Added ICE candidate")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse ICE candidate: ${e.message}")
                    }
                }

                SignalType.CALL_END -> {
                    cleanup()
                }

                else -> {} // Handled by CallViewModel
            }
        }
    }

    private fun cleanup() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        localVideoSource?.dispose()
        localAudioSource?.dispose()
        peerConnection?.connection?.close()
        peerConnection = null
        _isMuted.value = false
        _isCameraOff.value = false
        Log.d(TAG, "WebRTC cleanup complete")
    }

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack
    fun getRemoteVideoTrack(): VideoTrack? {
        return peerConnection?.connection?.receivers
            ?.flatMap { it.streams }
            ?.flatMap { it.videoTracks }
            ?.firstOrNull()
    }
}
