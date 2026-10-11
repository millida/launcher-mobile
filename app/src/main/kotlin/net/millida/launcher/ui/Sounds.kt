package net.millida.launcher.ui

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.millida.launcher.Prefs
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.media.UiSounds
import net.millida.launcher.core.media.UiSoundsResult
import java.io.File
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "millida-sound"
private const val MAX_STREAMS = 8
private const val GESTURE_MS = 400L
private const val MIN_GAP_MS = 40L
private const val MIN_RATE = 0.5f
private const val MAX_RATE = 2f
private const val TAIL_HOLD = 0.6f
private const val TAIL_STEPS = 6
private const val RETRIES = 3
private const val RETRY_STEP_MS = 20_000L
private const val BURST_CARD_DELAY_MS = 180L
private const val GOLD_EPIC_DELAY_MS = 120L

private enum class Trigger { Press, Code, Sample }

/**
 * One player for the whole process. Every play goes through one thread, so the
 * throttle, the click scale and the press window need no locks.
 */
class UiSoundPlayer(
    private val prefs: Prefs,
    private val sounds: UiSounds,
) {
    private val handler = Handler(HandlerThread("millida-ui-sounds").apply { start() }.looper)
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val sampleByPath = ConcurrentHashMap<String, Int>()
    private val loaded: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
    private val prepareLock = Mutex()

    @Volatile
    private var samples: Map<UiSoundEvent, List<Int>> = emptyMap()

    @Volatile
    private var last: UiSoundsResult? = null

    private val lastPlayed = EnumMap<UiSoundEvent, Long>(UiSoundEvent::class.java)
    private var scaleStep = 0
    private var pressWindowUntil = 0L

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loaded.add(sampleId)
            } else {
                Log.w(TAG, "звук $sampleId не загрузился, код $status")
            }
        }
    }

    val hapticsEnabled: Boolean get() = prefs.haptics

    suspend fun prepare(): UiSoundsResult = withContext(Dispatchers.IO) { prepareLocked() }

    fun play(event: UiSoundEvent) {
        handler.post { emit(event, Trigger.Code, null) }
    }

    fun press(event: UiSoundEvent) {
        handler.post { emit(event, Trigger.Press, null) }
    }

    fun sample(event: UiSoundEvent, rate: Float, delayMs: Long = 0) {
        handler.postDelayed({ emit(event, Trigger.Sample, rate) }, delayMs)
    }

    fun chestHit(index: Int) {
        sample(UiSoundEvent.ChestHit, 0.85f + index * 0.12f)
        if (index >= 2) sample(UiSoundEvent.ChestCrack, 1.1f + index * 0.05f)
    }

    fun chestBurst() {
        sample(UiSoundEvent.ChestOpen, 1f)
        sample(UiSoundEvent.ChestCard, 1.3f, BURST_CARD_DELAY_MS)
    }

    fun chestCard(rank: Int) {
        when {
            rank >= 4 -> {
                sample(UiSoundEvent.ChestGold, 1f)
                sample(UiSoundEvent.ChestEpic, 1f, GOLD_EPIC_DELAY_MS)
            }
            rank >= 3 -> sample(UiSoundEvent.ChestEpic, 1f)
            rank >= 2 -> sample(UiSoundEvent.ChestRare, 1.1f)
            else -> sample(UiSoundEvent.ChestCard, 1f + rank * 0.15f)
        }
    }

    private suspend fun prepareLocked(): UiSoundsResult {
        prepareLock.withLock {
            val known = last
            if (known is UiSoundsResult.Ready) return known
            var current = attempt()
            var retries = 0
            while (current !is UiSoundsResult.Ready && retries < RETRIES) {
                retries++
                delay(RETRY_STEP_MS * retries)
                current = attempt()
            }
            return current
        }
    }

    private suspend fun attempt(): UiSoundsResult {
        val result = sounds.prepare()
        load(result.files)
        when (result) {
            is UiSoundsResult.Ready -> Unit
            is UiSoundsResult.Partial -> Log.w(
                TAG,
                "не хватает звуков: ${result.missing.joinToString { it.id }}: ${result.cause?.message}",
                result.cause,
            )
            is UiSoundsResult.Failed -> Log.w(TAG, "звуки интерфейса не скачались: ${result.cause.message}", result.cause)
        }
        last = result
        return result
    }

    private fun load(files: Map<UiSoundEvent, List<File>>) {
        val next = EnumMap<UiSoundEvent, List<Int>>(UiSoundEvent::class.java)
        for ((event, list) in files) {
            val ids = list.mapNotNull { sampleOf(it) }
            if (ids.isNotEmpty()) next[event] = ids
        }
        samples = samples + next
    }

    private fun sampleOf(file: File): Int? {
        sampleByPath[file.path]?.let { return it }
        val id = pool.load(file.path, 1)
        if (id == 0) {
            Log.w(TAG, "звук ${file.name} не открылся")
            return null
        }
        sampleByPath[file.path] = id
        return id
    }

    private fun emit(event: UiSoundEvent, trigger: Trigger, rate: Float?) {
        val mode = prefs.soundMode
        if (mode == Prefs.SOUND_OFF) return
        val now = SystemClock.uptimeMillis()
        when (trigger) {
            Trigger.Press -> {
                if (mode != Prefs.SOUND_ALL) return
                pressWindowUntil = now + GESTURE_MS
            }
            Trigger.Code -> if (event.ui && (mode != Prefs.SOUND_ALL || now < pressWindowUntil)) return
            Trigger.Sample -> Unit
        }
        val volume = prefs.soundVolume
        if (volume <= 0) return
        val previous = lastPlayed[event]
        if (previous != null && now - previous < maxOf(event.minGapMs, MIN_GAP_MS)) return
        val ids = samples[event]?.filter { it in loaded }.orEmpty()
        if (ids.isEmpty()) return
        lastPlayed[event] = now
        val level = (volume / 100f * event.gain).coerceIn(0f, 1f)
        val speed = (rate ?: nextRate(event)).coerceIn(MIN_RATE, MAX_RATE)
        val stream = pool.play(ids.random(), level, level, 1, 0, speed)
        if (stream != 0 && event.maxSeconds > 0f) fadeTail(stream, level, event.maxSeconds)
    }

    private fun nextRate(event: UiSoundEvent): Float {
        val rates = event.rates
        if (rates.size <= 1) return rates.firstOrNull() ?: 1f
        val rate = rates[scaleStep % rates.size]
        scaleStep = (scaleStep + 1) % rates.size
        return rate
    }

    private fun fadeTail(stream: Int, level: Float, seconds: Float) {
        val start = SystemClock.uptimeMillis()
        val total = (seconds * 1000).toLong()
        val hold = (total * TAIL_HOLD).toLong()
        for (step in 1..TAIL_STEPS) {
            val at = start + hold + (total - hold) * step / TAIL_STEPS
            val volume = level * (1f - step.toFloat() / TAIL_STEPS)
            handler.postAtTime({
                if (step == TAIL_STEPS) pool.stop(stream) else pool.setVolume(stream, volume, volume)
            }, at)
        }
    }
}

@Stable
interface UiFeedback {
    fun tap(event: UiSoundEvent = UiSoundEvent.Click)

    fun play(event: UiSoundEvent)

    fun chestHit(index: Int)

    fun chestBurst()

    fun chestCard(rank: Int)

    companion object {
        val None: UiFeedback = object : UiFeedback {
            override fun tap(event: UiSoundEvent) = Unit

            override fun play(event: UiSoundEvent) = Unit

            override fun chestHit(index: Int) = Unit

            override fun chestBurst() = Unit

            override fun chestCard(rank: Int) = Unit
        }
    }
}

val LocalUiFeedback = staticCompositionLocalOf<UiFeedback> { UiFeedback.None }

@Composable
fun ProvideUiFeedback(player: UiSoundPlayer, content: @Composable () -> Unit) {
    val view = LocalView.current
    val feedback = remember(player, view) { ViewUiFeedback(player, view) }
    CompositionLocalProvider(LocalUiFeedback provides feedback, content = content)
}

private class ViewUiFeedback(
    private val player: UiSoundPlayer,
    private val view: View,
) : UiFeedback {
    override fun tap(event: UiSoundEvent) {
        player.press(event)
        buzz(hapticOf(event))
    }

    override fun play(event: UiSoundEvent) = player.play(event)

    override fun chestHit(index: Int) {
        player.chestHit(index)
        buzz(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun chestBurst() {
        player.chestBurst()
        buzz(confirmHaptic())
    }

    override fun chestCard(rank: Int) = player.chestCard(rank)

    private fun buzz(kind: Int) {
        if (player.hapticsEnabled) view.performHapticFeedback(kind)
    }
}

private fun hapticOf(event: UiSoundEvent): Int = when (event) {
    UiSoundEvent.Error, UiSoundEvent.Crash -> rejectHaptic()
    UiSoundEvent.Success, UiSoundEvent.Install, UiSoundEvent.Achievement,
    UiSoundEvent.Login, UiSoundEvent.Launch, UiSoundEvent.Delete -> confirmHaptic()
    UiSoundEvent.ChestHit, UiSoundEvent.ChestCrack, UiSoundEvent.ChestOpen -> HapticFeedbackConstants.LONG_PRESS
    else -> HapticFeedbackConstants.KEYBOARD_TAP
}

private fun confirmHaptic(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP

private fun rejectHaptic(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
