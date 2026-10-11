package net.millida.launcher.core.rubies

/**
 * Which night market cards the player has turned over. Turning is only a
 * reveal of what is already known, so it is kept per market: a new market
 * (another closing time) starts face down again.
 */
object NightFlips {
    private const val SEPARATOR = '\n'
    private const val CODE_SEPARATOR = ','

    fun decode(raw: String?, endsAt: String): Set<String> {
        if (raw.isNullOrEmpty() || endsAt.isEmpty()) return emptySet()
        val stored = raw.substringBefore(SEPARATOR)
        if (stored != endsAt) return emptySet()
        return raw.substringAfter(SEPARATOR, "").split(CODE_SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    fun encode(endsAt: String, codes: Set<String>): String =
        endsAt + SEPARATOR + codes.filter { it.isNotBlank() && CODE_SEPARATOR !in it && SEPARATOR !in it }.joinToString(CODE_SEPARATOR.toString())
}
