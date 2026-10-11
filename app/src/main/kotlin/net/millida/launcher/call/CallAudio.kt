package net.millida.launcher.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.millida.launcher.Prefs
import net.millida.launcher.core.call.Ring
import net.millida.launcher.core.call.RingTones

/**
 * The phone side of a conversation's sound: the system is told this is a call,
 * other players duck, and the voice goes to the speaker unless a headset is in.
 */
class CallAudio(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var active = false

    fun start(speaker: Boolean) {
        val audio = manager ?: return
        if (!active) {
            previousMode = audio.mode
            active = true
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        if (focus == null && audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    /** A wired or Bluetooth headset wins over the speaker: nobody wants the call aloud with headphones on. */
    fun setSpeaker(on: Boolean) {
        val audio = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSETS }
            val target = when {
                headset != null -> headset
                on -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (target == null || !audio.setCommunicationDevice(target)) Log.i(TAG, "устройство звонка не выбралось")
        } else {
            val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on && !headset
        }
    }

    fun stop() {
        val audio = manager ?: return
        if (!active) return
        active = false
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = previousMode
    }

    private companion object {
        const val TAG = "millida-call"
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}

/**
 * The ring and the dialling tone, synthesised from the desktop note table. It
 * follows the launcher's own sound switch and volume, as the desktop ring does.
 */
class RingPlayer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Synchronized
    fun start(kind: Ring) {
        stop()
        val prefs = Prefs(context)
        if (prefs.soundMode == Prefs.SOUND_OFF) return
        val pattern = RingTones.PATTERNS.getValue(kind)
        val level = RingTones.level(prefs.soundVolume, pattern)
        if (level <= 0f) return
        val pcm = RingTones.render(pattern, level, SAMPLE_RATE)
        if (pcm.isEmpty()) return
        val usage = if (kind == Ring.INCOMING) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
        job = scope.launch {
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
            } catch (refused: Exception) {
                Log.w(TAG, "звонок не прозвучал: ${refused.message}")
                return@launch
            }
            try {
                track.write(pcm, 0, pcm.size)
                do {
                    track.stop()
                    track.reloadStaticData()
                    track.play()
                    delay(if (pattern.repeatMs > 0) pattern.repeatMs else SINGLE_MS)
                } while (isActive && pattern.repeatMs > 0)
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "millida-call"
        const val SAMPLE_RATE = 22_050
        const val SINGLE_MS = 1_200L
    }
}
