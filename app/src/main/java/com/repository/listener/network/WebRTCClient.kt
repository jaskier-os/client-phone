package com.repository.listener.network

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import com.repository.listener.BuildConfig
import com.repository.listener.util.LogCollector
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRTCClient(context: Context) {

    companion object {
        private const val TAG = "WebRTCClient"
        private const val ICE_GATHER_TIMEOUT_MS = 10_000L
        private const val ICE_DISCONNECT_GRACE_MS = 15_000L
        // STUN is always available (public Google STUN). A TURN relay is optional and
        // only added when TURN_URL is configured at build time (see local.properties.example).
        private val ICE_SERVERS: List<PeerConnection.IceServer> = buildList {
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            if (BuildConfig.TURN_URL.isNotBlank()) {
                add(
                    PeerConnection.IceServer.builder(BuildConfig.TURN_URL)
                        .setUsername(BuildConfig.TURN_USERNAME)
                        .setPassword(BuildConfig.TURN_PASSWORD)
                        .createIceServer()
                )
            }
        }
    }

    interface Listener {
        fun onWebRTCAnswer(streamId: Int, sdp: String)
        fun onWebRTCAudioConnected(streamId: Int, peerSeq: Long)
        fun onWebRTCDisconnected(streamId: Int, peerSeq: Long)
    }

    private val factory: PeerConnectionFactory
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var peerConnection: PeerConnection? = null

    // Monotonic per-peer id. Stream ids restart low on each transport, so a new offer
    // can reuse the id of the peer it replaces; the closing peer's disconnect would then
    // match the new session's id and tear it down. This never repeats.
    private var peerSeqCounter = 0L
    @Volatile
    var currentPeerSeq = 0L
        private set
    private var currentStreamId = 0
    private var answerSent = false
    private var disconnectRunnable: Runnable? = null
    var listener: Listener? = null

    // Periodic inbound-audio stats: NetEq concealment counters directly measure
    // audible glitches and attribute them to packet loss (network) vs jitter
    // (late arrival) vs device underruns.
    private var statsRunnable: Runnable? = null
    private var lastPacketsReceived = 0L
    private var lastPacketsLost = 0L
    private var lastConcealmentEvents = 0L
    private var lastConcealedSamples = 0L
    private var lastInsertedForDecel = 0L
    private var lastRemovedForAccel = 0L

    private fun startStatsLogging() {
        stopStatsLogging()
        val runnable = object : Runnable {
            override fun run() {
                val pc = peerConnection ?: return
                pc.getStats { report ->
                    for (stats in report.statsMap.values) {
                        if (stats.type != "inbound-rtp") continue
                        if ((stats.members["kind"] as? String) != "audio") continue
                        val received = (stats.members["packetsReceived"] as? Number)?.toLong() ?: 0
                        val lost = (stats.members["packetsLost"] as? Number)?.toLong() ?: 0
                        val jitter = (stats.members["jitter"] as? Number)?.toDouble() ?: 0.0
                        val jbDelay = (stats.members["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0
                        val jbEmitted = (stats.members["jitterBufferEmittedCount"] as? Number)?.toLong() ?: 0
                        val concealEv = (stats.members["concealmentEvents"] as? Number)?.toLong() ?: 0
                        val concealed = (stats.members["concealedSamples"] as? Number)?.toLong() ?: 0
                        val decel = (stats.members["insertedSamplesForDeceleration"] as? Number)?.toLong() ?: 0
                        val accel = (stats.members["removedSamplesForAcceleration"] as? Number)?.toLong() ?: 0
                        val dRecv = received - lastPacketsReceived
                        val dLost = lost - lastPacketsLost
                        val dConcealEv = concealEv - lastConcealmentEvents
                        val dConcealed = concealed - lastConcealedSamples
                        val dDecel = decel - lastInsertedForDecel
                        val dAccel = accel - lastRemovedForAccel
                        lastPacketsReceived = received; lastPacketsLost = lost
                        lastConcealmentEvents = concealEv; lastConcealedSamples = concealed
                        lastInsertedForDecel = decel; lastRemovedForAccel = accel
                        val jbAvgMs = if (jbEmitted > 0) jbDelay / jbEmitted * 1000 else 0.0
                        LogCollector.i(TAG, "audio-stats: recv=+$dRecv lost=+$dLost jitter=${"%.1f".format(jitter * 1000)}ms " +
                            "jbAvg=${"%.0f".format(jbAvgMs)}ms concealEv=+$dConcealEv concealed=+${dConcealed}smp " +
                            "decel=+${dDecel}smp accel=+${dAccel}smp")
                    }
                }
                handler.postDelayed(this, 5000)
            }
        }
        statsRunnable = runnable
        handler.postDelayed(runnable, 5000)
    }

    private fun stopStatsLogging() {
        statsRunnable?.let { handler.removeCallbacks(it) }
        statsRunnable = null
        lastPacketsReceived = 0; lastPacketsLost = 0
        lastConcealmentEvents = 0; lastConcealedSamples = 0
        lastInsertedForDecel = 0; lastRemovedForAccel = 0
    }

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(audioAttributes)
            .createAudioDeviceModule()

        val options = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        LogCollector.i(TAG, "PeerConnectionFactory initialized")
    }

    fun handleOffer(streamId: Int, sdp: String) {
        // Close the previous peer BEFORE adopting the new stream id. close() drives the old
        // observer to CLOSED, which reports a disconnect; if currentStreamId already pointed
        // at the new stream that disconnect looked like the NEW stream failing instantly and
        // defeated the service's stale-stream guard, spawning an endless retry storm.
        answerSent = false
        handler.removeCallbacksAndMessages(null)
        close()
        currentStreamId = streamId

        // Report events against the stream this peer was created for, never the shared
        // mutable field that a newer offer may already have overwritten.
        val peerStreamId = streamId
        peerSeqCounter += 1
        val peerSeq = peerSeqCounter
        currentPeerSeq = peerSeq

        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                LogCollector.d(TAG, "ICE candidate gathered: ${candidate.sdp.take(60)}")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                LogCollector.i(TAG, "ICE gathering state: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    handler.post { sendAnswerIfReady("gathering complete") }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                LogCollector.i(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        disconnectRunnable?.let {
                            handler.removeCallbacks(it)
                            disconnectRunnable = null
                            LogCollector.i(TAG, "ICE recovered to $state, cancelled disconnect timer")
                        }
                        // Observer callbacks arrive on the WebRTC signaling thread; the
                        // listener mutates audio-relay state owned by the main thread.
                        handler.post { listener?.onWebRTCAudioConnected(peerStreamId, peerSeq) }
                        startStatsLogging()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        if (disconnectRunnable == null) {
                            LogCollector.w(TAG, "ICE disconnected, starting ${ICE_DISCONNECT_GRACE_MS}ms grace period")
                            val streamIdSnapshot = peerStreamId
                            val runnable = Runnable {
                                disconnectRunnable = null
                                LogCollector.w(TAG, "ICE did not recover within grace period, firing disconnect for stream $streamIdSnapshot")
                                listener?.onWebRTCDisconnected(streamIdSnapshot, peerSeq)
                            }
                            disconnectRunnable = runnable
                            handler.postDelayed(runnable, ICE_DISCONNECT_GRACE_MS)
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        disconnectRunnable?.let {
                            handler.removeCallbacks(it)
                            disconnectRunnable = null
                        }
                        handler.post { listener?.onWebRTCDisconnected(peerStreamId, peerSeq) }
                    }
                    else -> {}
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is AudioTrack) {
                    LogCollector.i(TAG, "Remote audio track received")
                    track.setEnabled(true)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                LogCollector.i(TAG, "Connection state: $state")
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                LogCollector.i(TAG, "Track received: ${transceiver.mediaType}")
            }
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        })

        val pc = peerConnection ?: return

        val offerSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                LogCollector.i(TAG, "Remote description set, creating answer")
                createAnswer(pc)
            }
            override fun onSetFailure(error: String) {
                LogCollector.e(TAG, "Failed to set remote description: $error")
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, offerSdp)
    }

    private fun createAnswer(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        LogCollector.i(TAG, "Local description set, waiting for ICE candidates (timeout ${ICE_GATHER_TIMEOUT_MS}ms)")
                        // Send answer after timeout or when gathering completes, whichever first
                        handler.postDelayed({
                            sendAnswerIfReady("timeout")
                        }, ICE_GATHER_TIMEOUT_MS)
                    }
                    override fun onSetFailure(error: String) {
                        LogCollector.e(TAG, "Failed to set local description: $error")
                    }
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String) {
                LogCollector.e(TAG, "Failed to create answer: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    private fun sendAnswerIfReady(reason: String) {
        if (answerSent) return
        answerSent = true
        handler.removeCallbacksAndMessages(null)
        val pc = peerConnection ?: return
        val localSdp = pc.localDescription ?: return
        LogCollector.i(TAG, "Sending answer (reason: $reason)")
        listener?.onWebRTCAnswer(currentStreamId, localSdp.description)
    }

    fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun close() {
        stopStatsLogging()
        disconnectRunnable?.let {
            handler.removeCallbacks(it)
            disconnectRunnable = null
        }
        handler.removeCallbacksAndMessages(null)
        peerConnection?.close()
        peerConnection = null
        answerSent = false
    }

    fun release() {
        close()
        factory.dispose()
        LogCollector.i(TAG, "WebRTC client released")
    }
}
