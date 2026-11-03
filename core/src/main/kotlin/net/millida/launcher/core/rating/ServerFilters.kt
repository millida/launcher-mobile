package net.millida.launcher.core.rating

import net.millida.launcher.core.i18n.tr

/** The rating's facets as the desktop launcher names them (src/screens/Servers.tsx, src/state/servers.ts). */
object ServerFilters {
    val SORTS: List<RatingSort> = listOf(RatingSort.RATING, RatingSort.ONLINE, RatingSort.NAME, RatingSort.NEW)

    val CATEGORIES: List<Pair<String, String>> get() = listOf(
        "" to tr("Все"),
        "SURVIVAL" to tr("Выживание"),
        "ANARCHY" to tr("Анархия"),
        "MINIGAMES" to tr("Мини-игры"),
        "MMORPG" to tr("РПГ"),
        "VANILLA" to tr("Ванилла"),
        "MODDED" to tr("Моды"),
        "SKYBLOCK" to "SkyBlock",
        "CREATIVE" to tr("Креатив"),
        "PVP" to "PvP",
    )

    val ONLINE: List<Pair<String, String>> get() = listOf(
        "" to tr("Любой онлайн"),
        LIVE to tr("Сейчас есть игроки"),
        "20" to tr("От 20 игроков"),
        "100" to tr("От 100 игроков"),
    )

    const val LIVE = "live"

    fun sortLabel(sort: RatingSort): String = when (sort) {
        RatingSort.RATING -> tr("По рейтингу")
        RatingSort.ONLINE -> tr("По онлайну")
        RatingSort.NAME -> tr("По названию")
        RatingSort.NEW -> tr("Сначала новые")
    }

    fun licenseLabel(license: LicenseFilter): String = when (license) {
        LicenseFilter.ANY -> tr("Любая лицензия")
        LicenseFilter.CRACKED -> tr("Без лицензии")
        LicenseFilter.LICENSE -> tr("Только лицензия")
    }

    fun categoryLabel(code: String): String? = CATEGORIES.firstOrNull { it.first == code }?.second

    fun onlineLabel(value: String): String? = ONLINE.firstOrNull { it.first == value }?.second

    /** «Сейчас есть игроки» is one player or more; a threshold goes as is; anything else adds no parameter. */
    fun minOnline(value: String): Int = if (value == LIVE) 1 else value.toIntOrNull()?.coerceAtLeast(0) ?: 0
}

/**
 * The stand-in for a server without a logo: a Minecraft block on a dark tint,
 * both picked from the name, so one server always gets the same pair. FNV-1a
 * over UTF-16 units, as the desktop does it in JavaScript.
 */
object ServerArt {
    val BLOCKS: List<Int> = listOf(
        1, 3, 5, 6, 7, 8, 9, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 26, 27, 28,
        32, 33, 34, 36, 37, 38, 40, 42, 43, 44, 47, 48, 51, 52, 53, 54,
    )

    const val TINTS = 8

    fun hashOf(text: String): Long {
        var hash = 0x811C9DC5.toInt()
        for (unit in text) {
            hash = hash xor unit.code
            hash *= 16777619
        }
        return hash.toLong() and 0xFFFFFFFFL
    }

    fun keyOf(server: RatedServer): String = server.slug.ifEmpty { server.name.ifEmpty { server.ip } }

    fun block(key: String): Int = BLOCKS[(hashOf(key) % BLOCKS.size).toInt()]

    fun tint(key: String): Int = (hashOf(key) % TINTS).toInt()

    fun blockAsset(block: Int): String = "file:///android_asset/block-icons/Block${block}Millida.png"
}
