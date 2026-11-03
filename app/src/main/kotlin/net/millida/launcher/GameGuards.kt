package net.millida.launcher

import android.content.Context
import net.millida.launcher.core.runtime.BootPicture
import net.millida.launcher.core.runtime.ExitHook
import java.io.File

/**
 * What a start of the game learned about this phone and has to find again on the next one.
 * Kept in files of its own and not in the settings: those are shared with the launcher
 * process, which writes its whole copy back and loses what the game process added.
 */
internal class GameGuards(context: Context) {
    private val dir = File(context.filesDir, DIR)

    /** The mark of ExitHook: starts in a row that installed the exit interceptor and proved nothing. */
    val exitHook: String get() = read(EXIT_HOOK)

    /** False when the mark could not be written: without it a failing interceptor would be installed on every start. */
    fun countExitHook(version: String, unconfirmed: Int): Boolean = write(EXIT_HOOK, ExitHook.mark(version, unconfirmed))

    /** The game drew, or the Java machine left with a code of its own: the interceptor did no harm on this start. */
    fun confirmExitHook() {
        if (exitHook.isNotEmpty()) write(EXIT_HOOK, "")
    }

    /** Reading the game's picture back gave a wrong answer on this phone, see BootPicture.DISTRUST_AFTER_MS. */
    fun pictureProbeOff(version: String): Boolean = read(PICTURE_PROBE) == BootPicture.distrust(version)

    fun distrustPictureProbe(version: String): Boolean = write(PICTURE_PROBE, BootPicture.distrust(version))

    private fun read(name: String): String = runCatching { File(dir, name).readText().trim() }.getOrDefault("")

    private fun write(name: String, value: String): Boolean = runCatching {
        dir.mkdirs()
        File(dir, name).writeText(value)
    }.isSuccess

    private companion object {
        const val DIR = "game-guards"
        const val EXIT_HOOK = "exit-hook"
        const val PICTURE_PROBE = "picture-probe"
    }
}
