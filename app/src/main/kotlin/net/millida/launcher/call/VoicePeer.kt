package net.millida.launcher.call

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import net.millida.launcher.core.call.CallRules
import net.millida.launcher.core.call.CallSignalKind
import net.millida.launcher.core.call.IceConfig
import net.millida.launcher.core.call.PeerFlags
import net.millida.launcher.core.call.PeerQuality
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VoicePeerEvents {
    fun onSignal(kind: CallSignalKind, data: JsonObject)

    fun onFlags(flags: PeerFlags)

    fun onConnection(state: PeerConnection.PeerConnectionState)
}

/**
 * One connection to one person, the phone side of src/lib/call/peer.ts. Only
 * voice travels: a desktop showing its screen or camera offers video too, and
 * the phone declines those sections, so the desktop simply has nobody to show to.
 *
 * Politeness is handed out once and by the same rule on both ends, so two
 * offers that cross are settled without a race: the polite side rolls back.
 */
class VoicePeer(
    factory: PeerConnectionFactory,
    ice: IceConfig,
    private val polite: Boolean,
    private val events: VoicePeerEvents,
) {
    @Volatile
    private var makingOffer = false

    @Volatile
    private var closed = false

    private var micSender: RtpSender? = null
    private val remoteAudio = ArrayList<AudioTrack>()
    private var volume = 1.0
    private var deafened = false
    private var lastBytes = 0L
    private var lastAt = 0L

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null || closed) return
            events.onSignal(CallSignalKind.ICE, buildJsonObject { put("candidate", candidateJson(candidate)) })
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) = Unit

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(channel: DataChannel?) = Unit

        override fun onRenegotiationNeeded() {
            if (!closed) VoiceEngine.post { negotiate() }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (newState != null && !closed) events.onConnection(newState)
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() as? AudioTrack ?: return
            VoiceEngine.post {
                if (closed) return@post
                remoteAudio += track
                applyVolume(track)
            }
        }
    }

    private val pc: PeerConnection = factory.createPeerConnection(rtcConfig(ice), observer)
        ?: throw IllegalStateException("голосовое соединение не создалось")

    private val channel: DataChannel? = pc.createDataChannel(
        "state",
        DataChannel.Init().apply {
            negotiated = true
            id = 0
        },
    )?.also { created ->
        created.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() = Unit

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null || buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val parsed = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)) as? JsonObject
                }.getOrNull() ?: return
                events.onFlags(PeerFlags.parse(parsed))
            }
        })
    }

    fun setMic(track: AudioTrack) {
        if (closed) return
        val sender = micSender
        if (sender != null) {
            sender.setTrack(track, false)
        } else {
            micSender = pc.addTrack(track, listOf(STREAM_ID))
        }
    }

    /** Whether the flags left: a closed channel is the caller's cue to send them by signalling. */
    fun sendFlags(flags: PeerFlags): Boolean {
        val open = channel?.state() == DataChannel.State.OPEN
        if (!open || closed) return false
        val bytes = flags.toJson().toString().toByteArray(StandardCharsets.UTF_8)
        return channel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) == true
    }

    fun setVolume(pct: Int) {
        volume = pct.coerceIn(0, 200) / 100.0
        remoteAudio.forEach(::applyVolume)
    }

    fun setDeafened(value: Boolean) {
        deafened = value
        remoteAudio.forEach(::applyVolume)
    }

    suspend fun accept(kind: CallSignalKind, data: JsonObject) {
        if (closed) return
        if (kind == CallSignalKind.ICE) {
            val candidate = parseCandidate(data["candidate"] as? JsonObject) ?: return
            if (!pc.addIceCandidate(candidate)) Log.i(TAG, "кандидат не принят — пришёл раньше описания или устарел")
            return
        }
        val description = parseDescription(data["sdp"] as? JsonObject) ?: return
        val isOffer = description.type == SessionDescription.Type.OFFER
        val collision = isOffer && (makingOffer || pc.signalingState() != PeerConnection.SignalingState.STABLE)
        if (!polite && collision) return
        if (collision) {
            runCatching { setLocal(SessionDescription(SessionDescription.Type.ROLLBACK, "")) }
                .onFailure { Log.i(TAG, "откат предложения не прошёл: ${it.message}") }
        }
        setRemote(description)
        if (!isOffer || closed) return
        val answer = create(offer = false)
        setLocal(answer)
        events.onSignal(CallSignalKind.ANSWER, descriptionJson(pc.localDescription ?: answer))
    }

    suspend fun quality(): PeerQuality = suspendCancellableCoroutine { cont ->
        if (closed) {
            cont.resume(PeerQuality(null, 0.0, 0.0))
            return@suspendCancellableCoroutine
        }
        pc.getStats { report ->
            var rttMs: Long? = null
            var lost = 0L
            var received = 0L
            var bytes = 0L
            report.statsMap.values.forEach { stat ->
                val members = stat.members
                if (stat.type == "candidate-pair" && members["state"] == "succeeded") {
                    (members["currentRoundTripTime"] as? Number)?.let { rttMs = Math.round(it.toDouble() * 1000) }
                }
                if (stat.type == "inbound-rtp" && members["kind"] == "audio") {
                    lost = (members["packetsLost"] as? Number)?.toLong() ?: 0L
                    received = (members["packetsReceived"] as? Number)?.toLong() ?: 0L
                    bytes = (members["bytesReceived"] as? Number)?.toLong() ?: 0L
                }
            }
            val now = System.nanoTime() / 1_000_000
            val kbps = if (lastAt > 0 && bytes >= lastBytes && now > lastAt) (bytes - lastBytes) * 8.0 / (now - lastAt) else 0.0
            lastBytes = bytes
            lastAt = now
            if (cont.isActive) cont.resume(PeerQuality(rttMs, CallRules.lossPct(lost, received), kbps))
        }
    }

    fun close() {
        if (closed) return
        closed = true
        remoteAudio.clear()
        runCatching { channel?.unregisterObserver() }
        runCatching { channel?.close() }
        runCatching { channel?.dispose() }
        runCatching { pc.close() }
        runCatching { pc.dispose() }
    }

    private suspend fun negotiate() {
        if (closed) return
        try {
            makingOffer = true
            val offer = create(offer = true)
            setLocal(offer)
            events.onSignal(CallSignalKind.OFFER, descriptionJson(pc.localDescription ?: offer))
        } catch (failed: IllegalStateException) {
            Log.w(TAG, "пересогласование сорвалось: ${failed.message}")
        } finally {
            makingOffer = false
        }
    }

    private fun applyVolume(track: AudioTrack) {
        runCatching {
            track.setEnabled(!deafened)
            track.setVolume(volume * VOLUME_SCALE)
        }
    }

    private suspend fun create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val sdp = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (!cont.isActive) return
                if (description == null) cont.resumeWithException(IllegalStateException("пустое описание"))
                else cont.resume(description)
            }

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "описание не собралось"))
            }

            override fun onSetFailure(error: String?) = Unit
        }
        if (offer) pc.createOffer(sdp, MediaConstraints()) else pc.createAnswer(sdp, MediaConstraints())
    }

    private suspend fun setLocal(description: SessionDescription) = applyDescription(description, local = true)

    private suspend fun setRemote(description: SessionDescription) = applyDescription(description, local = false)

    private suspend fun applyDescription(description: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { cont ->
        val sdp = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit

            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onCreateFailure(error: String?) = Unit

            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "описание не принято"))
            }
        }
        if (local) pc.setLocalDescription(sdp, description) else pc.setRemoteDescription(sdp, description)
    }

    companion object {
        private const val TAG = "millida-call"
        private const val STREAM_ID = "millida-voice"

        /** The engine takes 0..10 as a gain; the desktop slider's 100% is unity. */
        private const val VOLUME_SCALE = 1.0

        fun rtcConfig(ice: IceConfig): PeerConnection.RTCConfiguration {
            val servers = ice.servers.map { spec ->
                PeerConnection.IceServer.builder(spec.urls)
                    .apply {
                        spec.username?.let { setUsername(it) }
                        spec.credential?.let { setPassword(it) }
                    }
                    .createIceServer()
            }
            return PeerConnection.RTCConfiguration(servers).apply {
                iceTransportsType = if (ice.relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
        }

        /** The browser's RTCSessionDescription.toJSON(): what the desktop sends and expects. */
        fun descriptionJson(description: SessionDescription): JsonObject = buildJsonObject {
            put(
                "sdp",
                buildJsonObject {
                    put("type", description.type.canonicalForm())
                    put("sdp", description.description)
                },
            )
            put("video", buildJsonObject { })
        }

        fun parseDescription(raw: JsonObject?): SessionDescription? {
            val type = (raw?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val text = (raw["sdp"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val parsed = when (type) {
                "offer" -> SessionDescription.Type.OFFER
                "answer" -> SessionDescription.Type.ANSWER
                "pranswer" -> SessionDescription.Type.PRANSWER
                else -> return null
            }
            return SessionDescription(parsed, text)
        }

        /** The browser's RTCIceCandidate.toJSON() field names. */
        fun candidateJson(candidate: IceCandidate): JsonObject = buildJsonObject {
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
        }

        fun parseCandidate(raw: JsonObject?): IceCandidate? {
            if (raw == null) return null
            val text = (raw["candidate"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val mid = (raw["sdpMid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val index = (raw["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull
            if (mid == null && index == null) return null
            return IceCandidate(mid.orEmpty(), index ?: 0, text)
        }
    }
}
