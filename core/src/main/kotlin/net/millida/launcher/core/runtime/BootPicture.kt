package net.millida.launcher.core.runtime

/** What a look at the game's surface found. */
enum class Picture {
    /** Something is drawn: a logo, a loading bar, a menu. */
    LIT,

    /** The surface is there and black, or holds no frame yet. */
    DARK,

    /** There is no surface to look at: the player is in another application. */
    HIDDEN,

    /** The phone did not let the surface be read. */
    UNKNOWN,
}

enum class BootStep { WAIT, SHOW, BLACK }

/**
 * When the loading screen may leave a game that already hands frames over.
 *
 * [BootWatch] keeps the loading screen while there is no frame at all. But the first
 * frames are black: the window opens long before anything is drawn into it, so the
 * player looked at a black screen with the controls over it until the game's own
 * loading picture came, and took it for a hang. The picture itself is the only honest
 * sign, so it is read back from the surface and the loading screen stays until
 * something is in it.
 *
 * A black picture alone says nothing: a pack of two hundred mods keeps its window black
 * for minutes while it loads. In the first hour of 1.9.26 half a minute of black was
 * taken for a black screen, and 14 of 25 such verdicts were 1.7.10 packs still loading
 * their mods. So, as for a start with no frame at all, the game's log decides: while
 * it is being written the game is loading and the loading screen stays.
 *
 * Reading the surface back is new and no phone confirmed it, so every way it can be
 * wrong ends in the old behaviour — the game shown on its first frame — and never in
 * a game locked behind the loading screen.
 */
object BootPicture {
    const val SAMPLE_WIDTH = 48
    const val SAMPLE_HEIGHT = 27

    /** A renderer whose manifest entry sets this to 0 keeps the old rule: a switch that needs no release. */
    const val SWITCH = "MILLIDA_PICTURE_PROBE"

    /**
     * The player still plays this long after a black screen was declared: it was the
     * reading that was wrong, not the picture, and this phone goes back to the old rule.
     */
    const val DISTRUST_AFTER_MS = 180_000L

    private const val LIT_CHANNEL = 28
    private const val LIT_ONE_IN = 200

    fun enabled(rendererEnvironment: Map<String, String>): Boolean = rendererEnvironment[SWITCH] != "0"

    fun of(argb: IntArray): Picture {
        if (argb.isEmpty()) return Picture.DARK
        val lit = argb.count { pixel ->
            maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF) >= LIT_CHANNEL
        }
        return if (lit * LIT_ONE_IN >= argb.size) Picture.LIT else Picture.DARK
    }

    /** [log] — what [BootWatch] makes of the game's log at this moment. */
    fun step(picture: Picture, log: BootWatch.Verdict): BootStep = when (picture) {
        Picture.LIT, Picture.UNKNOWN -> BootStep.SHOW
        Picture.HIDDEN -> BootStep.WAIT
        Picture.DARK -> when (log) {
            BootWatch.Verdict.WAITING, BootWatch.Verdict.LOADING -> BootStep.WAIT
            BootWatch.Verdict.SILENT, BootWatch.Verdict.OVERDUE -> BootStep.BLACK
        }
    }

    /**
     * The mark of a phone where the reading was wrong. It holds for one version of the
     * launcher: the next one may read better, and 1.9.26 left its marks for the wrong reason.
     */
    fun distrust(version: String): String = "off:$version"
}
