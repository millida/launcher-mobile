package net.millida.launcher.ui.home

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.media.LobbyMusic
import net.millida.launcher.core.media.MusicTrack
import java.io.File
import kotlin.random.Random

data class WelcomeAsk(val first: Boolean, val nick: String)

data class AmbientState(
    val musicOn: Boolean = false,
    val musicAtStart: Boolean = true,
    val musicVolume: Int = DEFAULT_MUSIC_VOLUME,
    val playing: Boolean = false,
    val nowPlaying: String = "",
    val musicError: String? = null,
    val welcome: WelcomeAsk? = null,
    val tourStep: Int? = null,
)

const val DEFAULT_MUSIC_VOLUME = 30

/**
 * Everything around the lobby that is not a button: the music, the «С
 * возвращением» splash and the lobby guide. The music is the game's own
 * soundtrack from the asset store; it plays only while the launcher is on
 * screen and never over the player's own music.
 */
class AmbientModel(
    context: Context,
    private val music: LobbyMusic,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mutable = MutableStateFlow(
        AmbientState(
            musicOn = prefs.getBoolean(KEY_MUSIC_ON, false),
            musicAtStart = prefs.getBoolean(KEY_MUSIC_AT_START, true),
            musicVolume = prefs.getInt(KEY_MUSIC_VOLUME, DEFAULT_MUSIC_VOLUME).coerceIn(0, 100),
        ),
    )
    val state: StateFlow<AmbientState> = mutable.asStateFlow()

    private var player: MediaPlayer? = null
    private var tracks: List<MusicTrack> = emptyList()
    private var position = -1
    private var resumed = false
    private var wantedThisRun = mutable.value.musicOn && mutable.value.musicAtStart
    private var startJob: Job? = null
    private var welcomeDone = false

    // --- Music ---

    fun setMusicOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_MUSIC_ON, on).apply()
        wantedThisRun = on
        mutable.update { it.copy(musicOn = on, musicError = null) }
        sync()
    }

    fun setMusicAtStart(on: Boolean) {
        prefs.edit().putBoolean(KEY_MUSIC_AT_START, on).apply()
        mutable.update { it.copy(musicAtStart = on) }
    }

    fun setMusicVolume(percent: Int) {
        val value = percent.coerceIn(0, 100)
        prefs.edit().putInt(KEY_MUSIC_VOLUME, value).apply()
        mutable.update { it.copy(musicVolume = value) }
        player?.let { applyVolume(it, value) }
        sync()
    }

    fun onResumed(isResumed: Boolean) {
        resumed = isResumed
        sync()
    }

    private fun shouldPlay(): Boolean {
        val current = mutable.value
        return resumed && wantedThisRun && current.musicOn && current.musicVolume > 0
    }

    private fun sync() {
        if (!shouldPlay()) {
            startJob?.cancel()
            startJob = null
            player?.takeIf { it.isPlaying }?.pause()
            mutable.update { it.copy(playing = false) }
            return
        }
        val existing = player
        if (existing != null) {
            if (!existing.isPlaying) existing.start()
            mutable.update { it.copy(playing = true) }
            return
        }
        if (startJob?.isActive == true) return
        // Someone else's music on the phone wins: the lobby does not talk over it.
        if (audio?.isMusicActive == true) return
        startJob = scope.launch { playNext() }
    }

    private suspend fun playNext() {
        try {
            if (tracks.isEmpty()) {
                tracks = music.tracks()
                position = if (tracks.isEmpty()) -1 else Random.nextInt(tracks.size) - 1
            }
            if (tracks.isEmpty()) {
                mutable.update { it.copy(musicError = "Музыки игры нет — проверь интернет", playing = false) }
                return
            }
            position = (position + 1) % tracks.size
            val track = tracks[position]
            val file = music.file(track)
            if (!shouldPlay()) return
            start(file, track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LOG_TAG, "музыка лобби не запустилась: ${error.message}")
            mutable.update { it.copy(musicError = "Музыка не скачалась — проверь интернет", playing = false) }
        }
    }

    private suspend fun start(file: File, track: MusicTrack) {
        release()
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            withContext(Dispatchers.IO) {
                created.setDataSource(file.absolutePath)
                created.prepare()
            }
        } catch (error: Exception) {
            created.release()
            throw error
        }
        created.setOnCompletionListener { done ->
            if (player === done) {
                release()
                if (shouldPlay()) startJob = scope.launch { playNext() }
            }
        }
        created.setOnErrorListener { failed, _, _ ->
            if (player === failed) release()
            mutable.update { it.copy(playing = false) }
            true
        }
        player = created
        applyVolume(created, 0)
        created.start()
        mutable.update { it.copy(playing = true, nowPlaying = track.title, musicError = null) }
        fadeIn(created)
    }

    private suspend fun fadeIn(target: MediaPlayer) {
        for (step in 1..FADE_STEPS) {
            delay(FADE_STEP_MS)
            if (player !== target) return
            applyVolume(target, mutable.value.musicVolume * step / FADE_STEPS)
        }
    }

    private fun applyVolume(target: MediaPlayer, percent: Int) {
        val level = (percent.coerceIn(0, 100) / 100f).let { it * it }
        runCatching { target.setVolume(level, level) }
    }

    private fun release() {
        val old = player ?: return
        player = null
        runCatching { old.release() }
    }

    // --- Welcome splash ---

    /** Once per launch, after the first-run screens; the very first launch says «Добро пожаловать». */
    fun askWelcome(nick: String) {
        if (welcomeDone) return
        welcomeDone = true
        val first = !prefs.getBoolean(KEY_WELCOMED, false)
        if (first) prefs.edit().putBoolean(KEY_WELCOMED, true).apply()
        mutable.update { it.copy(welcome = WelcomeAsk(first, nick)) }
    }

    /** The first run already greets with its own screens: this launch skips the splash. */
    fun skipWelcome() {
        welcomeDone = true
        prefs.edit().putBoolean(KEY_WELCOMED, true).apply()
    }

    fun closeWelcome() = mutable.update { it.copy(welcome = null) }

    // --- Lobby guide ---

    fun startTour() = mutable.update { it.copy(tourStep = 0) }

    fun tourStep(step: Int) = mutable.update { it.copy(tourStep = step) }

    fun closeTour() = mutable.update { it.copy(tourStep = null) }

    fun close() {
        startJob?.cancel()
        release()
    }

    private companion object {
        const val PREFS = "millida-ambient"
        const val KEY_MUSIC_ON = "music_on"
        const val KEY_MUSIC_AT_START = "music_at_start"
        const val KEY_MUSIC_VOLUME = "music_volume"
        const val KEY_WELCOMED = "welcomed"
        const val LOG_TAG = "millida"
        const val FADE_STEPS = 9
        const val FADE_STEP_MS = 100L
    }
}
