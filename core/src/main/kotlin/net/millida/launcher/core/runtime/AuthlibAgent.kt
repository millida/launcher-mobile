package net.millida.launcher.core.runtime

import kotlinx.coroutines.CancellationException
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import java.io.File
import java.io.IOException

const val AUTHLIB_INJECTOR_VERSION = "1.2.8"
const val AUTHLIB_INJECTOR_SHA256 = "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10"
const val AUTHLIB_INJECTOR_URL =
    "https://github.com/yushijinhun/authlib-injector/releases/download/v$AUTHLIB_INJECTOR_VERSION/authlib-injector-$AUTHLIB_INJECTOR_VERSION.jar"
const val AUTHLIB_INJECTOR_FEED_URL =
    "https://authlib-injector.yushi.moe/artifact/56/authlib-injector-$AUTHLIB_INJECTOR_VERSION.jar"

/**
 * The Millida session only works through authlib-injector: without the agent
 * the game checks a Millida token against Mojang, falls back to offline, and a
 * player loses multiplayer and, on a vanilla build, the skin. The agent is a
 * program loaded into the game, so only the pinned build with its digest is
 * ever used - the same one the desktop launcher pins.
 */
class AuthlibAgent(
    private val layout: GameLayout,
    private val downloader: Downloader,
) {
    val jar: File
        get() = File(layout.runtimes, JAR_NAME)

    fun ready(): Boolean = Hashing.matches(jar, AUTHLIB_INJECTOR_SHA256)

    /**
     * The working copy is replaced only by a file that already passed the digest,
     * so a failed download never leaves the player without the agent they had.
     */
    suspend fun ensure(): File {
        if (ready()) return jar
        val failures = ArrayList<String>()
        for (url in sources()) {
            try {
                downloader.fetch(RemoteFile(url = url, target = jar, sha1 = AUTHLIB_INJECTOR_SHA256))
                if (ready()) return jar
                failures += "${hostOf(url)}: файл не прошёл проверку"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += "${hostOf(url)}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        throw IOException("authlib-injector не скачался (${failures.joinToString("; ")})")
    }

    companion object {
        const val JAR_NAME = "authlib-injector.jar"

        fun sources(): List<String> = listOf(
            AUTHLIB_INJECTOR_URL,
            AUTHLIB_INJECTOR_FEED_URL,
        )

        private fun hostOf(url: String): String =
            runCatching { java.net.URI(url).host }.getOrNull() ?: url
    }
}
