package net.millida.launcher.core.media

import kotlin.math.ceil
import kotlin.math.hypot

enum class LiveWallpaperKind { SCENE, VIDEO }

/**
 * Live lobby backgrounds, the desktop's choice (src/lib/wallpaper.ts and the
 * picker in Play.tsx): the pixel scene drawn by code and the four bundled clips.
 * Nothing chosen is the static background the lobby always had.
 */
data class LiveWallpaper(val id: String, val name: String, val kind: LiveWallpaperKind) {

    val clip: String get() = if (kind == LiveWallpaperKind.VIDEO) "$FOLDER/$id.mp4" else ""

    val poster: String get() = if (kind == LiveWallpaperKind.VIDEO) "$FOLDER/$id.jpg" else ""

    companion object {
        const val OFF = ""
        const val FOLDER = "wallpapers"

        val ALL: List<LiveWallpaper> = listOf(
            LiveWallpaper("pixels", "Сцена", LiveWallpaperKind.SCENE),
            LiveWallpaper("bg1", "Вишнёвая роща", LiveWallpaperKind.VIDEO),
            LiveWallpaper("bg2", "Уютная пещера", LiveWallpaperKind.VIDEO),
            LiveWallpaper("bg3", "Солнечный город", LiveWallpaperKind.VIDEO),
            LiveWallpaper("bg4", "У камина", LiveWallpaperKind.VIDEO),
        )

        /** An id another build stored and this one does not know means the static background. */
        fun of(id: String?): LiveWallpaper? = ALL.firstOrNull { it.id == id }

        fun known(id: String?): String = of(id)?.id ?: OFF

        /** Moves only with motion allowed by the player and the phone, and only while the app is in front. */
        fun animates(chosen: LiveWallpaper?, motionOn: Boolean, reducedMotion: Boolean, resumed: Boolean): Boolean =
            chosen != null && motionOn && !reducedMotion && resumed
    }
}

/**
 * The scene's wave, as the desktop's PixelField draws it: every 6-9 s a ragged
 * front crosses the grid, each cell flashes fast and fades slowly in four steps.
 */
object SceneWave {
    const val CELL_DP = 36f
    const val SPEED_DP_PER_MS = 0.9f
    const val FLASH_MS = 520f
    const val HOT_MS = 90f
    const val FIRST_MS = 1_200L
    const val GAP_MIN_MS = 6_000L
    const val GAP_SPREAD_MS = 3_000L
    const val JITTER_CELLS = 3f
    private const val DISTANCE_SHARE = 0.72f
    private const val STEPS = 4f

    /** Milliseconds since the front reached a cell [distanceDp] away, late by its own jitter. */
    fun sinceFront(ageMs: Float, distanceDp: Float, jitterCells: Float): Float =
        ageMs - (distanceDp * DISTANCE_SHARE + jitterCells * CELL_DP) / SPEED_DP_PER_MS

    fun level(sinceFrontMs: Float): Float {
        if (sinceFrontMs <= 0f || sinceFrontMs >= FLASH_MS) return 0f
        val raw = if (sinceFrontMs < HOT_MS) sinceFrontMs / HOT_MS else 1f - (sinceFrontMs - HOT_MS) / (FLASH_MS - HOT_MS)
        return ceil(raw * STEPS) / STEPS
    }

    /** A wave is over once the front has crossed the diagonal and the last, most jittered cell has faded. */
    fun lifeMs(widthDp: Float, heightDp: Float): Float =
        (hypot(widthDp, heightDp) * DISTANCE_SHARE + JITTER_CELLS * CELL_DP) / SPEED_DP_PER_MS + FLASH_MS
}

/** Experience-orb sparks around the figure: they fade in, twinkle and fade out in four brightness steps. */
object SceneSpark {
    private const val RISE = 0.15f
    private const val FADE_FROM = 0.7f
    private const val STEPS = 4f

    /** [progress] is the share of the spark's life gone, [twinkle] the sine of its own clock. */
    fun alpha(progress: Float, twinkle: Float): Float {
        if (progress < 0f || progress > 1f) return 0f
        val envelope = when {
            progress < RISE -> progress / RISE
            progress > FADE_FROM -> (1f - progress) / (1f - FADE_FROM)
            else -> 1f
        }
        val shimmer = 0.6f + 0.4f * twinkle.coerceIn(-1f, 1f)
        return ceil(envelope * shimmer * STEPS).coerceAtLeast(0f) / STEPS
    }
}
