package net.millida.launcher.core.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant

import net.millida.launcher.core.i18n.tr
/** A rating category as an activity: BedWars is chosen first, the server inside it second. */
data class HubMode(
    val code: String,
    val title: String,
    val total: Int,
    val online: Int,
    val slugs: List<String>,
)

enum class HealthTone { OK, WARN }

data class PackHealth(
    val slug: String,
    val status: String,
    val launches: Int,
    val successPct: Int?,
    val lastOkAt: String?,
)

/** «Работает · запускали 2 ч назад · 97% запусков без ошибок»; a warning offers a report. */
data class HealthLine(
    val tone: HealthTone,
    val head: String,
    val whenText: String,
    val pct: Int?,
    val report: Boolean,
)

enum class PresetLoader(val id: String) {
    VANILLA("vanilla"),
    FABRIC("fabric"),
    FORGE("forge"),
    NEOFORGE("neoforge"),
    QUILT("quilt");

    companion object {
        fun of(raw: String?): PresetLoader? = entries.firstOrNull { it.id == raw }
    }
}

data class PopularVersion(val mc: String, val loader: PresetLoader)

/**
 * The «Во что играем» hub without the network: the answers of `/rating/modes`,
 * `/launcher/pack-health` and `/launcher/popular-versions` read the way the
 * desktop hub reads them, so both show the same modes, numbers and versions.
 */
object Hub {
    const val OWN_MODE = "ONEBLOCK"

    /** Below this an online number is not shown at all: a small live number reads as an empty server. */
    const val ONLINE_SHOWN_FROM = 20

    private val MODE_CODE = Regex("^[A-Z_]{2,24}$")
    private val VERSION = Regex("^\\d+(\\.\\d+)+$")
    private const val POPULAR_LIMIT = 3

    /** Used when the server does not answer: the top three by launches over 30 days (desktop, 23.09.2026). */
    val POPULAR_FALLBACK = listOf(
        PopularVersion("1.20.1", PresetLoader.FORGE),
        PopularVersion("26.2", PresetLoader.FABRIC),
        PopularVersion("1.21.11", PresetLoader.FABRIC),
    )

    /** The same titles the desktop hub and millida.net/katalog print for a rating code. */
    private val TITLES get() = mapOf(
        "BEDWARS" to "BedWars",
        "SKYWARS" to "SkyWars",
        "SKYBLOCK" to "SkyBlock",
        "ANARCHY" to tr("Анархия"),
        "ONEBLOCK" to "OneBlock",
        "LIFESTEAL" to "LifeSteal",
        "BOXPVP" to "BoxPvP",
        "PRISON" to tr("Тюрьма"),
        "MINIGAMES" to tr("Мини-игры"),
        "SURVIVAL" to tr("Выживание"),
        "PVP" to "PvP",
        "GRIEF" to tr("Гриф"),
        "FACTIONS" to tr("Кланы"),
        "MMORPG" to tr("РПГ"),
        "HARDCORE" to tr("Хардкор"),
        "CREATIVE" to tr("Креатив"),
        "PARKOUR" to tr("Паркур"),
        "LUCKY" to tr("Лаки-блоки"),
        "VANILLA" to tr("Ванилла"),
        "ROLEPLAY" to "RP",
        "TECHNIC" to tr("Техно"),
        "ADVENTURE" to tr("Приключения"),
        "ECONOMY" to tr("Экономика"),
        "MODDED" to tr("Моды"),
        "OTHER" to tr("Разное"),
        "RP" to "RP",
        "HIDE_SEEK" to tr("Прятки"),
        "KITPVP" to "KitPvP",
        "BUILD_BATTLE" to "Build Battle",
        "TECHNICAL" to tr("Техно"),
        "SPLEEF" to "Spleef",
        "UHC" to "UHC",
        "TNTRUN" to "TNT Run",
        "HUNGER_GAMES" to tr("Голодные игры"),
        "TOWNY" to "Towny",
        "COBBLEMON" to tr("Пиксельмон"),
        "MURDER" to "Murder Mystery",
        "EARTH" to tr("Земля"),
        "MANHUNT" to tr("Манхант"),
        "BINGO" to tr("Бинго"),
    )

    fun modeTitle(code: String): String =
        TITLES[code] ?: (code.take(1) + code.drop(1).lowercase().replace('_', ' '))

    /**
     * Only codes the rating really filters by and that have servers; the
     * busiest first, our own event pinned to the front.
     */
    fun modes(body: JsonElement?): List<HubMode> {
        val list = ((body as? JsonObject)?.get("modes") as? JsonArray).orEmpty()
        return list.mapNotNull { element ->
            val mode = element as? JsonObject ?: return@mapNotNull null
            val code = mode.text("code").takeIf { MODE_CODE.matches(it) } ?: return@mapNotNull null
            val slugs = mode.strings("slugs")
            if (slugs.isEmpty()) return@mapNotNull null
            HubMode(
                code = code,
                title = modeTitle(code),
                total = mode.int("total") ?: slugs.size,
                online = (mode.int("online") ?: 0).coerceAtLeast(0),
                slugs = slugs,
            )
        }
            .sortedByDescending { it.online }
            .sortedByDescending { it.code == OWN_MODE }
    }

    /** 163 400 → «163 тыс», so the name keeps its line. */
    fun shortOnline(value: Int): String = when {
        value >= 10_000 -> tr("{0} тыс", Math.round(value / 1000.0))
        value >= 1_000 -> {
            val tenths = Math.round(value / 100.0)
            val whole = tenths / 10
            val rest = tenths % 10
            if (rest == 0L) tr("{whole} тыс", whole) else tr("{whole},{rest} тыс", whole, rest)
        }
        else -> value.toString()
    }

    fun onlineLabel(value: Int?): String? = value?.takeIf { it >= ONLINE_SHOWN_FROM }?.let { tr("{0} играют", shortOnline(it)) }

    fun health(body: JsonElement?, slug: String): PackHealth? {
        val key = slug.trim().lowercase()
        val packs = ((body as? JsonObject)?.get("packs") as? JsonArray).orEmpty()
        return packs.mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("slug") == key }
            ?.let { pack ->
                PackHealth(
                    slug = key,
                    status = pack.text("status"),
                    launches = pack.int("launches") ?: 0,
                    successPct = pack.int("successPct"),
                    lastOkAt = pack.text("lastOkAt").ifEmpty { null },
                )
            }
    }

    /** Null when there is nothing to say: no line on the page at all. */
    fun healthLine(health: PackHealth?, now: Instant = Instant.now()): HealthLine? {
        if (health == null || health.status == "unknown") return null
        val since = health.lastOkAt?.let { ago(it, now) }.orEmpty()
        val whenText = if (since.isNotEmpty()) tr("запускали {since}", since) else ""
        if (health.status == "warn") return HealthLine(HealthTone.WARN, tr("Бывают сбои"), whenText, health.successPct, report = true)
        if (whenText.isEmpty() && health.successPct == null) return null
        return HealthLine(HealthTone.OK, tr("Работает"), whenText, health.successPct, report = false)
    }

    fun ago(iso: String, now: Instant): String {
        val at = runCatching { Instant.parse(iso.trim()) }.getOrNull() ?: return ""
        val minutes = Duration.between(at, now).toMillis() / 60_000.0
        return when {
            minutes < 5 -> tr("только что")
            minutes < 60 -> tr("{0} мин назад", Math.round(minutes))
            minutes < 1440 -> tr("{0} ч назад", Math.round(minutes / 60))
            else -> tr("{0} дн назад", Math.round(minutes / 1440))
        }
    }

    fun popularVersions(body: JsonElement?): List<PopularVersion> {
        val list = ((body as? JsonObject)?.get("versions") as? JsonArray).orEmpty()
        val parsed = list.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val mc = (row["mc"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim() ?: return@mapNotNull null
            if (!VERSION.matches(mc)) return@mapNotNull null
            val loader = PresetLoader.of((row["loader"] as? JsonPrimitive)?.contentOrNull) ?: return@mapNotNull null
            PopularVersion(mc, loader)
        }.take(POPULAR_LIMIT)
        return parsed.ifEmpty { POPULAR_FALLBACK }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value.isString) return null
        return value.longOrNull?.toInt() ?: value.doubleOrNull?.let { Math.round(it).toInt() }
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
