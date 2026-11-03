package net.millida.launcher.core

import net.millida.launcher.core.runtime.BootPicture
import net.millida.launcher.core.runtime.BootStep
import net.millida.launcher.core.runtime.BootWatch
import net.millida.launcher.core.runtime.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootPictureTest {
    private val size = BootPicture.SAMPLE_WIDTH * BootPicture.SAMPLE_HEIGHT
    private val black = 0xFF000000.toInt()

    private fun sample(lit: Int, colour: Int): IntArray = IntArray(size) { if (it < lit) colour else black }

    private class Look(val why: String, val pixels: IntArray, val verdict: Picture)

    @Test
    fun `a picture counts as drawn only when something is really in it`() {
        val looks = listOf(
            Look("the red Mojang screen is the game loading: its own progress must be seen", sample(size, 0xFFEF323D.toInt()), Picture.LIT),
            Look("a white logo on black covers a few percent of the screen and is still a picture", sample(size / 40, 0xFFFFFFFF.toInt()), Picture.LIT),
            Look("a frame that was only cleared is the black screen the player complained about", sample(0, black), Picture.DARK),
            Look("noise near black is not a picture: a cleared frame is rarely exactly zero", sample(size, 0xFF0A0A0A.toInt()), Picture.DARK),
            Look("two stray bright pixels are not a logo", sample(2, 0xFFFFFFFF.toInt()), Picture.DARK),
            Look("transparent black is what an untouched surface reads as", sample(0, 0), Picture.DARK),
            Look("no pixels at all means no frame was handed over yet", IntArray(0), Picture.DARK),
        )
        looks.forEach { look ->
            assertEquals("${look.why} — otherwise the loading screen leaves too early or never", look.verdict, BootPicture.of(look.pixels))
        }
    }

    @Test
    fun `reading the picture is on unless the manifest switches it off`() {
        assertTrue("no switch in the manifest must not disable it", BootPicture.enabled(emptyMap()))
        assertFalse("0 brings the old rule back without a release", BootPicture.enabled(mapOf(BootPicture.SWITCH to "0")))
    }

    private class Moment(val why: String, val picture: Picture, val log: BootWatch.Verdict, val step: BootStep)

    @Test
    fun `over a black picture the game's log decides, as it does with no frame at all`() {
        val moments = listOf(
            Moment("a drawn picture is shown at once, whatever the log does", Picture.LIT, BootWatch.Verdict.SILENT, BootStep.SHOW),
            Moment("black in the first minute: nothing is judged yet", Picture.DARK, BootWatch.Verdict.WAITING, BootStep.WAIT),
            Moment("black and the log is alive: a pack of two hundred mods is loading, the loading screen stays", Picture.DARK, BootWatch.Verdict.LOADING, BootStep.WAIT),
            Moment("black and the log went quiet: a black screen, shown and reported", Picture.DARK, BootWatch.Verdict.SILENT, BootStep.BLACK),
            Moment("black at the far limit: shown anyway, the game is never locked away for good", Picture.DARK, BootWatch.Verdict.OVERDUE, BootStep.BLACK),
            Moment("in another application nothing can be seen, so nothing is concluded", Picture.HIDDEN, BootWatch.Verdict.SILENT, BootStep.WAIT),
            Moment("a phone that cannot read the surface keeps the old rule: frames mean a picture", Picture.UNKNOWN, BootWatch.Verdict.WAITING, BootStep.SHOW),
        )
        moments.forEach { moment ->
            assertEquals(
                "${moment.why} — the player either stares at a black screen or is locked behind the loading one",
                moment.step,
                BootPicture.step(moment.picture, moment.log),
            )
        }
    }

    @Test
    fun `a phone is distrusted for one version of the launcher only`() {
        assertEquals("off:1.9.28", BootPicture.distrust("1.9.28"))
        assertFalse(
            "the mark 1.9.26 left for the wrong reason must not switch the reading off in a later version",
            BootPicture.distrust("1.9.28") == "off",
        )
    }
}
