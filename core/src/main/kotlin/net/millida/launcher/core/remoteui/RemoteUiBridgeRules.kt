package net.millida.launcher.core.remoteui

/**
 * Which API paths a website screen may call and which links it may open. The same lists as the desktop
 * launcher's `remoteUiRules.ts`: wallet, account and chats stay closed to website code.
 */
object RemoteUiBridgeRules {
    const val SITE = "https://millida.net"

    private val ALLOW = listOf(
        Regex("^/hosting/"),
        Regex("^/catalog/"),
        Regex("^/rating/"),
        Regex("^/vds/"),
        Regex("^/forum/articles/[\\w-]+/(?:review|like)$"),
    )
    private val PREMIUM_READ = listOf(Regex("^/launcher/premium(?:/[\\w-]+)?$"))
    private val PREMIUM_POST = listOf(Regex("^/launcher/premium/subscribe$"), Regex("^/launcher/premium/[\\w-]+/buy$"))
    private val READ = listOf(
        Regex("^/forum/articles/[\\w-]+(?:/reviews)?$"),
        Regex("^/launcher/mr/v2/search\\?"),
        Regex("^/launcher/cf/v1/mods/search\\?"),
        Regex("^/launcher/pack-health\\?"),
    )
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

    fun apiAllowed(path: String, method: String): Boolean {
        if (method !in METHODS || path.contains("..") || path.contains("\\")) return false
        if (method == "GET" && (READ.any { it.containsMatchIn(path) } || PREMIUM_READ.any { it.containsMatchIn(path) })) return true
        if (method == "POST" && PREMIUM_POST.any { it.containsMatchIn(path) }) return true
        return ALLOW.any { it.containsMatchIn(path) }
    }

    /** A site path of the screen made into an address, or null when it is not a plain path of the site. */
    fun sitePath(path: String): String? =
        if (path.startsWith("/") && !path.startsWith("//") && !path.contains("\\")) SITE + path else null

    /** Only plain https links leave the app through a website screen. */
    fun externalAllowed(url: String): Boolean = url.startsWith("https://") && !url.contains(' ')
}
