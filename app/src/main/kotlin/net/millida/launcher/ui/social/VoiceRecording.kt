package net.millida.launcher.ui.social

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.millida.launcher.core.friends.VoiceNotes
import java.io.File
import java.io.IOException

/** A finished take: the file stays until the upload has read it, then [discard] removes it. */
class VoiceTake(private val file: File, val durationMs: Long, val peaks: List<Int>) {
    fun read(): ByteArray = file.readBytes()

    fun discard() {
        if (file.exists() && !file.delete()) Log.w(TAG, "запись голосового не удалилась: ${file.name}")
    }
}

/** [tooShort] is the player's slip, told as a hint; anything else is a real failure. */
class VoiceRecordFailure(message: String, cause: Throwable? = null, val tooShort: Boolean = false) : IOException(message, cause)

/**
 * One voice note being recorded: AAC in an MP4 box, mono, 64 kbit/s, so the
 * desktop limit of two minutes is about a megabyte, well under the server's three.
 * The level is sampled ten times a second for the live strip and the envelope.
 */
class VoiceRecorder private constructor(
    private val recorder: MediaRecorder,
    private val file: File,
    scope: CoroutineScope,
    onLevel: (level: Float, ms: Long) -> Unit,
) {
    private val startedAt = SystemClock.elapsedRealtime()
    private val levels = ArrayList<Float>()
    private var finished = false

    private val sampler: Job = scope.launch(Dispatchers.Main.immediate) {
        while (isActive && !finished) {
            val level = runCatching { recorder.maxAmplitude / AMPLITUDE_MAX }.getOrDefault(0f).coerceIn(0f, 1f)
            levels += level
            onLevel(level, elapsed())
            delay(SAMPLE_MS)
        }
    }

    fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt

    /**
     * Stops and hands over the take. MediaRecorder throws when it caught no
     * audio at all: that case and a too short take read as "too short", the
     * player's own mistake rather than a broken phone.
     */
    fun stop(): VoiceTake {
        if (finished) throw VoiceRecordFailure("Запись уже закончена")
        finished = true
        sampler.cancel()
        val duration = elapsed().coerceAtMost(VoiceNotes.MAX_MS)
        val stopped = try {
            recorder.stop()
            true
        } catch (empty: RuntimeException) {
            false
        } finally {
            recorder.release()
        }
        if (!stopped || duration < VoiceNotes.MIN_MS) {
            file.delete()
            throw VoiceRecordFailure("Слишком коротко — держи запись дольше", tooShort = true)
        }
        return VoiceTake(file, duration, VoiceNotes.envelope(levels))
    }

    fun cancel() {
        if (finished) return
        finished = true
        sampler.cancel()
        runCatching { recorder.stop() }
        recorder.release()
        file.delete()
    }

    companion object {
        private const val SAMPLE_MS = 100L
        private const val AMPLITUDE_MAX = 32_767f
        private const val SAMPLE_RATE = 44_100
        private const val BIT_RATE = 64_000

        /** [onLimit] fires on the main thread when the two minutes run out; the caller sends what was said. */
        fun start(
            context: Context,
            scope: CoroutineScope,
            onLevel: (level: Float, ms: Long) -> Unit,
            onLimit: () -> Unit,
        ): VoiceRecorder {
            val dir = File(context.cacheDir, "voice").apply { mkdirs() }
            val file = File(dir, "take-" + System.currentTimeMillis() + ".m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else legacyRecorder()
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(SAMPLE_RATE)
                recorder.setAudioEncodingBitRate(BIT_RATE)
                recorder.setMaxDuration(VoiceNotes.MAX_MS.toInt())
                recorder.setOutputFile(file.absolutePath)
                recorder.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) scope.launch(Dispatchers.Main.immediate) { onLimit() }
                }
                recorder.prepare()
                recorder.start()
            } catch (failed: Exception) {
                recorder.release()
                file.delete()
                throw VoiceRecordFailure("Микрофон не включился — его могла занять другая программа или звонок", failed)
            }
            return VoiceRecorder(recorder, file, scope, onLevel)
        }

        @Suppress("DEPRECATION")
        private fun legacyRecorder(): MediaRecorder = MediaRecorder()
    }
}

private const val TAG = "millida-voice"
