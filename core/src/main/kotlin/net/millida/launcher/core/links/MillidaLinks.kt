package net.millida.launcher.core.links

import net.millida.launcher.core.packs.PackCodes
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** What a `millida://` address asks the launcher to do, after it passed every check. */
sealed interface MillidaLink {
    /**
     * `install/<section>/<slug>` from the site's «Установить» button, and the
     * older `modpack/<slug>` ([packOnly]), which always meant a ready catalogue pack.
     */
    data class Install(
        val section: String,
        val slug: String,
        val versions: List<String> = emptyList(),
        val loaders: List<String> = emptyList(),
        val name: String? = null,
        val slim: Boolean = false,
        val packOnly: Boolean = false,
    ) : MillidaLink

    data class Project(val slug: String, val modpack: Boolean) : MillidaLink

    data class Join(
        val address: String,
        val name: String,
        val licensed: Boolean,
        val versions: List<String>,
    ) : MillidaLink

    data class Pack(val code: String) : MillidaLink

    /** `invite/<CODE>` from a friend's referral link: the code is offered, never applied by itself. */
    data class Invite(val code: String) : MillidaLink
}

/**
 * Any web page can open a `millida://` address, so everything that does not
 * look exactly like one of ours is dropped whole instead of being repaired:
 * a "fixed" slug is a slug nobody asked for.
 */
object MillidaLinks {
    const val SCHEME = "millida"
    const val APP_HOST = "millida.net"
    private const val APP_PACK_PATH = "p"

    val INSTALL_SECTIONS: List<String> = listOf(
        "mods",
        "modpacks",
        "texture-packs",
        "shaders",
        "data-packs",
        "maps",
        "addons",
        "cheats",
        "skins",
        "capes",
    )

    private const val MAX_LINK = 2048
    private const val NAME_MAX = 60
    private const val MAX_VERSIONS = 8
    private const val MAX_LOADERS = 4
    private const val MAX_HOST = 253

    // Skins are addressed by a player's nick, so case and underscore stay; dots
    // and slashes that could steer the request into another path do not.
    private val SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")
    private val VERSION = Regex("^[0-9a-z][0-9a-z.+_-]{0,31}$", RegexOption.IGNORE_CASE)
    private val LOADER = Regex("^[a-z][a-z0-9_-]{0,23}$")
    private val CODE_SHAPE = Regex("^[A-Za-z0-9-]{8,16}$")
    private val INVITE_CODE = Regex("^[A-Za-z0-9]{1,16}$")
    private val HOST_LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f<>]")
    private val SPACES = Regex("\\s+")

    fun parse(raw: String?): MillidaLink? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_LINK) return null
        val uri = try {
            URI(text)
        } catch (malformed: URISyntaxException) {
            return null
        }
        if ("https".equals(uri.scheme, ignoreCase = true)) return appLink(uri)
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) || uri.isOpaque) return null
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        val action = uri.host?.lowercase() ?: return null
        val parts = segments(uri.rawPath.orEmpty()) ?: return null
        val query = query(uri.rawQuery) ?: return null
        return when (action) {
            "install", "modpack" -> install(action, parts, query)
            "project" -> project(parts, query)
            "join" -> join(parts, query)
            "pack" -> pack(parts, query)
            "invite" -> invite(parts, query)
            else -> null
        }
    }

    /** A caption a link supplies is shown to the player, so it stays printable and short. */
    fun linkName(raw: String?): String? {
        val name = raw.orEmpty()
            .replace(CONTROL, " ")
            .replace(SPACES, " ")
            .trim()
            .take(NAME_MAX)
            .trim()
        return name.ifEmpty { null }
    }

    private fun install(action: String, parts: List<String>, query: Query): MillidaLink? {
        val (section, slug) = when (action) {
            "install" -> if (parts.size == 2) parts[0] to parts[1] else return null
            else -> if (parts.size == 1) "modpacks" to parts[0] else return null
        }
        if (section !in INSTALL_SECTIONS || !SLUG.matches(slug)) return null
        val versions = query.all("version").map { it.trim() }.filter { VERSION.matches(it) }.distinct().take(MAX_VERSIONS)
        val loaders = query.all("loader").map { it.trim().lowercase() }.filter { LOADER.matches(it) }.distinct().take(MAX_LOADERS)
        val skin = section == "skins"
        return MillidaLink.Install(
            section = section,
            slug = slug,
            versions = versions,
            loaders = loaders,
            name = if (skin || action == "modpack") linkName(query.first("name")) else null,
            slim = skin && query.first("model") == "slim",
            packOnly = action == "modpack",
        )
    }

    private fun project(parts: List<String>, query: Query): MillidaLink? {
        val slug = single(parts, query.first("slug")) ?: return null
        if (!SLUG.matches(slug)) return null
        return MillidaLink.Project(slug, modpack = query.first("type") == "modpack")
    }

    private fun join(parts: List<String>, query: Query): MillidaLink? {
        val address = single(parts, query.first("addr"))?.trim() ?: return null
        if (!addressValid(address)) return null
        val versions = query.all("version").map { it.trim() }.filter { VERSION.matches(it) }.distinct().take(MAX_VERSIONS)
        return MillidaLink.Join(
            address = address,
            name = linkName(query.first("name")) ?: address,
            licensed = query.first("licensed") == "1",
            versions = versions,
        )
    }

    private fun pack(parts: List<String>, query: Query): MillidaLink? {
        val code = single(parts, query.first("code")) ?: return null
        if (!CODE_SHAPE.matches(code) || !PackCodes.valid(code)) return null
        return MillidaLink.Pack(PackCodes.normalize(code))
    }

    private fun invite(parts: List<String>, query: Query): MillidaLink? {
        val code = single(parts, query.first("code")) ?: return null
        if (!INVITE_CODE.matches(code)) return null
        return MillidaLink.Invite(code.uppercase())
    }

    /**
     * `https://millida.net/p/<CODE>`, the address the share window hands out.
     * The system opens it here only for our verified host; anything else on
     * that host is a page of the site and stays in the browser. The query is
     * the site's own tracking and is not read.
     */
    private fun appLink(uri: URI): MillidaLink? {
        if (uri.isOpaque || uri.rawUserInfo != null || uri.port != -1) return null
        val host = uri.host?.lowercase() ?: return null
        if (host != APP_HOST && host != "www.$APP_HOST") return null
        val parts = segments(uri.rawPath.orEmpty()) ?: return null
        if (parts.size != 2 || parts[0] != APP_PACK_PATH) return null
        val code = parts[1]
        if (!CODE_SHAPE.matches(code) || !PackCodes.valid(code)) return null
        return MillidaLink.Pack(PackCodes.normalize(code))
    }

    /** The path names at most one thing; the query form is the older spelling of the same. */
    private fun single(parts: List<String>, fallback: String?): String? = when {
        parts.size == 1 -> parts[0]
        parts.isEmpty() -> fallback?.takeIf { it.isNotEmpty() }
        else -> null
    }

    /** host or host:port; IPv6 literals and anything with a path do not reach the game. */
    fun addressValid(address: String): Boolean {
        if (address.isEmpty() || address.length > MAX_HOST + 6) return false
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "")
        if (address.count { it == ':' } > 1) return false
        if (address.contains(':')) {
            val number = port.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.toIntOrNull()
                ?: return false
            if (number !in 1..65535) return false
        }
        if (host.isEmpty() || host.length > MAX_HOST) return false
        return host.split('.').all { HOST_LABEL.matches(it) }
    }

    private fun segments(rawPath: String): List<String>? {
        val parts = rawPath.split('/').filter { it.isNotEmpty() }
        return parts.map { decode(it) ?: return null }
    }

    private fun query(rawQuery: String?): Query? {
        if (rawQuery.isNullOrEmpty()) return Query(emptyList())
        val pairs = rawQuery.split('&').filter { it.isNotEmpty() }.map { pair ->
            val key = decode(pair.substringBefore('='), plusIsSpace = true) ?: return null
            val value = decode(pair.substringAfter('=', ""), plusIsSpace = true) ?: return null
            key to value
        }
        return Query(pairs)
    }

    private fun decode(raw: String, plusIsSpace: Boolean = false): String? = try {
        val prepared = if (plusIsSpace) raw else raw.replace("+", "%2B")
        URLDecoder.decode(prepared, Charsets.UTF_8.name())
    } catch (broken: IllegalArgumentException) {
        null
    }

    private class Query(private val pairs: List<Pair<String, String>>) {
        fun all(key: String): List<String> = pairs.filter { it.first == key }.map { it.second }

        fun first(key: String): String? = pairs.firstOrNull { it.first == key }?.second
    }
}
