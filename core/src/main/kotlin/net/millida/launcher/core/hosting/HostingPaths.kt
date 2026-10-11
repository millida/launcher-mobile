package net.millida.launcher.core.hosting

import net.millida.launcher.core.SafePath
import java.io.File

/**
 * A path typed on the phone must not be able to walk out of the server folder,
 * and the check happens here before anything reaches the network: a rejected
 * path never becomes a request. The walk-out rule is SafePath's, reused against
 * a throwaway root so there is one implementation of it and not two.
 */
object HostingPaths {

    private val ROOT: File by lazy {
        File(System.getProperty("java.io.tmpdir") ?: ".", "millida-hosting-path-check")
    }

    fun serverId(raw: String): String {
        val id = raw.trim()
        if (id.isEmpty()) throw HostingError("не указан сервер")
        if (id.contains('/') || id.contains('\\') || id.contains("..")) {
            throw HostingError("идентификатор сервера отклонён: $raw")
        }
        return id
    }

    /** Returns the normalised remote path, or throws with a player-readable reason. */
    fun remote(raw: String): String {
        val value = raw.trim().replace('\\', '/')
        if (value.isEmpty() || value == ".") return "."
        if (value.startsWith("/")) throw HostingError("путь на сервере отклонён как абсолютный: $raw")
        val target = try {
            SafePath.join(ROOT, value)
        } catch (rejected: Exception) {
            throw HostingError("путь на сервере отклонён: $raw", rejected)
        }
        val base = ROOT.canonicalPath
        val inside = target.canonicalPath
        if (inside == base) return "."
        return inside.removePrefix(base + File.separator).replace(File.separatorChar, '/')
    }

    fun join(dir: String, name: String): String {
        val cleanDir = remote(dir)
        val cleanName = remote(name)
        if (cleanDir == ".") return cleanName
        if (cleanName == ".") return cleanDir
        return "$cleanDir/$cleanName"
    }
}
