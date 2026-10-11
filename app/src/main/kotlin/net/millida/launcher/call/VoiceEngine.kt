package net.millida.launcher.call

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

/** The microphone of one conversation: opened for the call, closed with it. */
class MicTrack(private val source: AudioSource, val track: AudioTrack) {
    fun setMuted(muted: Boolean) {
        runCatching { track.setEnabled(!muted) }
    }

    fun close() {
        runCatching { track.setEnabled(false) }
        runCatching { track.dispose() }
        runCatching { source.dispose() }
    }
}

/**
 * The voice engine of the launcher process. Everything that touches a
 * connection runs on one thread: the engine calls back on threads of its own,
 * and two of them changing one call at once is how a call ends up with two
 * voices of the same person.
 */
object VoiceEngine {
    private const val TAG = "millida-call"

    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "millida-call") }
    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    var onMicFailure: ((String) -> Unit)? = null

    fun post(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "шаг звонка сорвался: ${error.message}")
            }
        }
    }

    /** False when the native part did not load on this device; the call then goes to the site. */
    fun available(context: Context): Boolean = runCatching { factory(context) }.isSuccess

    fun lastFailure(): String? = failure

    @Synchronized
    fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        failure?.let { throw IllegalStateException(it) }
        val app = context.applicationContext
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
            )
            val audio = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(RecordErrors)
                .createAudioDeviceModule()
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(audio)
                .createPeerConnectionFactory()
                .also { factory = it }
        } catch (broken: UnsatisfiedLinkError) {
            failure = "голосовой движок не загрузился: ${broken.message}"
            throw IllegalStateException(failure, broken)
        } catch (broken: RuntimeException) {
            failure = "голосовой движок не запустился: ${broken.message}"
            throw IllegalStateException(failure, broken)
        }
    }

    /** Echo, noise and level are handled by the engine on the capture itself, as the browser does on the desktop. */
    fun openMic(context: Context): MicTrack {
        val engine = factory(context)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = engine.createAudioSource(constraints)
        val track = engine.createAudioTrack("millida-mic", source)
        track.setEnabled(true)
        return MicTrack(source, track)
    }

    private object RecordErrors : JavaAudioDeviceModule.AudioRecordErrorCallback {
        override fun onWebRtcAudioRecordInitError(errorMessage: String?) = report(errorMessage)

        override fun onWebRtcAudioRecordStartError(
            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
            errorMessage: String?,
        ) = report(errorMessage)

        override fun onWebRtcAudioRecordError(errorMessage: String?) = report(errorMessage)

        private fun report(message: String?) {
            Log.w(TAG, "микрофон: $message")
            onMicFailure?.invoke("Микрофон не открылся — его могла занять другая программа")
        }
    }
}
