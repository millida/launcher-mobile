package net.millida.launcher.core.telemetry

import net.millida.launcher.core.support.ReportRedaction

/**
 * What goes into an event must not name the player: the desktop rules
 * (src/lib/telemetryPrivacy.ts) plus the phone's own folders, where the app
 * folder and the shared storage stand in for the desktop's home directory.
 */
object TelemetryPrivacy {
    private val APP_DATA = Regex("""/data/(?:user/\d+|data)/[\w.]+""")
    private val SHARED_STORAGE = Regex("""/(?:storage/emulated/\d+|sdcard|storage/[0-9A-F]{4}-[0-9A-F]{4})""", RegexOption.IGNORE_CASE)

    fun scrub(text: String): String =
        ReportRedaction.redact(text)
            .replace(APP_DATA, "<app>")
            .replace(SHARED_STORAGE, "<storage>")

    fun errorCode(text: String?, max: Int = CODE_MAX): String =
        scrub(text.orEmpty()).replace(CONTROL, " ").trim().take(max).ifEmpty { "unknown" }

    /** FNV-1a, 32 bits, over UTF-16 units: the same tag the desktop gives the same build name. */
    fun shortHash(text: String): String {
        var hash = FNV_OFFSET
        for (unit in text) {
            hash = hash xor unit.code
            hash *= FNV_PRIME
        }
        return hash.toUInt().toString(16).padStart(8, '0')
    }

    /** A catalogue build goes by its slug; a build the player named goes by a fingerprint of that name. */
    fun buildTag(name: String?, catalogSlug: String? = null): String? {
        if (name.isNullOrEmpty()) return null
        val slug = catalogSlug?.trim().orEmpty()
        if (slug.isNotEmpty()) return "catalog:" + slug.take(SLUG_MAX)
        return "custom:" + shortHash(name)
    }

    private val CONTROL = Regex("""[\u0000-\u001F\u007F]+""")
    private val FNV_OFFSET = 0x811c9dc5L.toInt()
    private const val FNV_PRIME = 0x01000193
    private const val CODE_MAX = 120
    private const val SLUG_MAX = 80
}
