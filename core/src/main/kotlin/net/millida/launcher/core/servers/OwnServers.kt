package net.millida.launcher.core.servers

import net.millida.launcher.core.packs.PhoneCard
import net.millida.launcher.core.packs.PhoneFit
import net.millida.launcher.core.rating.RatedServer

/**
 * A server joined only through its own client pack from our catalogue: a plain
 * Minecraft build never gets in. The rating hides its address, so its card is
 * recognised by slug, and a typed or shared address by host and port.
 */
data class OwnServer(
    val slug: String,
    val address: String,
    val pack: String,
    val title: String,
    /** What the catalogue card of [pack] says, so a list decides without reading it. */
    val phone: PhoneCard,
)

/** The same list the desktop launcher keeps; one place for the whole phone launcher. */
object OwnServers {
    /** OneBlock runs on 1.7.10 through its own client. */
    val ONEBLOCK = OwnServer(
        slug = "oneblock-7",
        address = "proxy-1.metalabsmc.net:25567",
        pack = "oneblock-metalabs",
        title = "OneBlock",
        phone = PhoneCard(loader = "forge", gameVersion = "1.7.10", ownLaunch = true),
    )

    val ALL: List<OwnServer> = listOf(ONEBLOCK)

    fun of(slug: String?, address: String?): OwnServer? {
        val key = address?.let(::canonical)?.takeIf { it.isNotEmpty() }
        return ALL.firstOrNull { own ->
            (!slug.isNullOrBlank() && own.slug == slug.trim()) || (key != null && canonical(own.address) == key)
        }
    }

    /** Whether the server's own pack may start on this phone, by [PhoneFit.listed]. */
    fun playable(
        own: OwnServer,
        legacyRuntime: Boolean = PhoneFit.legacyRuntime(),
        deviceTotalMb: Int = PhoneFit.deviceTotalMb(),
    ): Boolean = PhoneFit.listed(own.phone, legacyRuntime, deviceTotalMb)

    /**
     * Rating rows without the servers joined only by a pack this phone cannot
     * start: such a card has nothing to offer here.
     */
    fun forPhone(
        servers: List<RatedServer>,
        legacyRuntime: Boolean = PhoneFit.legacyRuntime(),
        deviceTotalMb: Int = PhoneFit.deviceTotalMb(),
    ): List<RatedServer> = servers.filter { server ->
        val own = of(server.slug, server.joinAddress) ?: return@filter true
        playable(own, legacyRuntime, deviceTotalMb)
    }

    /** `Host.Name.` and `host.name:25565` are one server, the way a game log and a rating card disagree. */
    fun canonical(address: String): String {
        val raw = address.trim().trimEnd('.')
        val port = raw.substringAfterLast(':', "").takeIf { it.length in 1..5 && it.all(Char::isDigit) }
        val host = (if (port != null) raw.substringBeforeLast(':') else raw).trimEnd('.').lowercase()
        if (host.isEmpty()) return ""
        val number = port?.toInt() ?: DEFAULT_PORT
        return if (number == DEFAULT_PORT) host else "$host:$number"
    }

    private const val DEFAULT_PORT = 25565
}
