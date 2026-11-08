package net.millida.launcher.core.content

import java.io.File

/**
 * Remembers that the pre-launch jar scans found nothing for a given state of the mods folder,
 * so the next start of an unchanged build does not read every jar again. Any difference in
 * the folder (a file added, removed, resized or touched, a .disabled toggled) changes the
 * signature and the scan runs again. A stored value that does not parse never matches.
 */
object PreLaunchCache {
    const val OFFER_TTL_MS = 6 * 60 * 60 * 1000L

    /** One file of the folder as it matters for the signature. */
    data class Entry(val name: String, val length: Long, val modified: Long)

    fun signature(entries: List<Entry>, context: String = ""): String {
        val body = entries.sortedBy { it.name }.joinToString("\n") { "${it.name}|${it.length}|${it.modified}" }
        // Not a security hash: a cheap, stable fingerprint of the listing.
        var h = 1125899906842597L
        for (c in context + "\u0000" + body) h = 31 * h + c.code
        return "${entries.size}:${java.lang.Long.toHexString(h)}"
    }

    fun signature(modsDir: File, context: String = ""): String {
        val files = modsDir.listFiles().orEmpty().filter { it.isFile }
        return signature(files.map { Entry(it.name, it.length(), it.lastModified()) }, context)
    }

    /** True when [stored] is the signature of a scan that found nothing and the folder is unchanged. */
    fun cleanFor(stored: String?, current: String): Boolean = !stored.isNullOrEmpty() && stored == current

    /** The last answer of the Millida mod check, tied to the folder state and the «declined» flag it was given under. */
    data class Offer(val action: LaunchActionName, val signature: String, val declined: Boolean, val savedAt: Long)

    enum class LaunchActionName { NONE, OFFER, UPDATE }

    fun encodeOffer(offer: Offer): String =
        listOf(offer.action.name, offer.declined, offer.savedAt, offer.signature).joinToString("\t")

    fun decodeOffer(raw: String?): Offer? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split('\t', limit = 4)
        if (parts.size != 4) return null
        val action = LaunchActionName.values().firstOrNull { it.name == parts[0] } ?: return null
        val declined = parts[1].toBooleanStrictOrNull() ?: return null
        val savedAt = parts[2].toLongOrNull() ?: return null
        return Offer(action, parts[3], declined, savedAt)
    }

    /** Usable now: same folder state, same «declined» answer, younger than the TTL and not from the future. */
    fun offerUsable(offer: Offer?, signature: String, declined: Boolean, now: Long): Boolean =
        offer != null && offer.signature == signature && offer.declined == declined &&
            now >= offer.savedAt && now - offer.savedAt < OFFER_TTL_MS
}
