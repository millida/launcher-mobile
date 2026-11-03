package net.millida.launcher.core.logs

import net.millida.launcher.core.i18n.tr

/**
 * What the player can do about a start that failed, when the game's own log
 * already names it. The most common failed start of a modded build is not a
 * crash at all: Fabric Loader refuses to load a mod whose dependency is absent.
 */
object CrashHints {
    private const val INCOMPATIBLE = "Incompatible mods found!"
    private val MISSING = Regex("""Mod '(.{1,80}?)' \(\S+\) \S+ requires .+? of ([a-z0-9][a-z0-9_.-]{0,63}), which is missing!""")
    private const val NAMED_MAX = 3
    private const val LINE_MAX = 600

    fun missingMods(log: String): String? {
        if (INCOMPATIBLE !in log) return null
        val asked = LinkedHashMap<String, String>()
        // The log is whatever the game and its mods print: a line is matched alone and
        // only while it is short, so no text in it can make the search run long.
        log.lineSequence().filter { it.length <= LINE_MAX }.forEach { line ->
            MISSING.find(line)?.let { match -> asked.putIfAbsent(match.groupValues[2], match.groupValues[1]) }
        }
        if (asked.isEmpty()) {
            return tr("Моды сборки не подходят друг другу или этой версии игры — что именно, написано в логе")
        }
        val named = asked.keys.take(NAMED_MAX).joinToString(", ")
        if (asked.size == 1) {
            val (dependency, owner) = asked.entries.first()
            return tr("Сборке не хватает мода {dependency}: его требует «{owner}». Поставь его в каталоге и запусти снова", dependency, owner)
        }
        if (asked.size > NAMED_MAX) {
            return tr("Сборке не хватает модов: {named} и ещё {0}. Поставь их в каталоге и запусти снова", named, asked.size - NAMED_MAX)
        }
        return tr("Сборке не хватает модов: {named}. Поставь их в каталоге и запусти снова", named)
    }
}
