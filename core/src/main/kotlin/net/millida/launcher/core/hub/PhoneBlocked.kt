package net.millida.launcher.core.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import net.millida.launcher.core.http.HttpError
import java.io.IOException

/** The list as the app keeps it between starts: the ids and when the server was last asked. */
data class PhoneBlockedCache(val ids: Set<String>, val fetchedAtMs: Long)

/**
 * What a pack costs a phone, from players' own phones: seconds to download and unpack it, seconds from
 * «Играть» to the first frame, and frames per second there (0 — not measured yet). Zero seconds is unknown.
 */
data class PackStat(val id: String, val bootSec: Int, val fps: Int, val samples: Int, val installSec: Int = 0) {
    /** «≈ 3 мин · 24 FPS» as the card prints it: install and start together, never shorter than a minute. */
    val minutes: Int get() = maxOf(1, (installSec + bootSec + 30) / 60)
}

/**
 * Packs that do not start on phones, from `GET /launcher/pack-health/phone-blocked`. The ids are
 * the very values the launcher sends as `pack`/`modpack` in `game_launch`: a catalogue slug,
 * a Modrinth project id or a CurseForge number. The list only hides what is OFFERED: a build
 * the player already has is never looked up here.
 */
object PhoneBlocked {
    const val PATH = "/launcher/pack-health/phone-blocked"
    const val REFRESH_MS = 60L * 60 * 1000

    @Volatile
    private var blocked: Set<String> = emptySet()

    fun set(ids: Set<String>) {
        blocked = ids
    }

    fun current(): Set<String> = blocked

    /** Garbage, an empty list or a missing `packs` is an empty set: nothing is hidden. */
    fun parse(body: JsonElement?): Set<String> {
        val rows = ((body as? JsonObject)?.get("packs") as? JsonArray) ?: return emptySet()
        return rows.mapNotNullTo(HashSet()) { row ->
            val id = ((row as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            normal(id)
        }
    }

    @Volatile
    private var stats: Map<String, PackStat> = emptyMap()

    fun setStats(next: Map<String, PackStat>) {
        stats = next
    }

    fun allStats(): Map<String, PackStat> = stats

    /** The first of [keys] the server has numbers for; null when it has none, and the card says nothing. */
    fun statFor(vararg keys: String?): PackStat? = keys.firstNotNullOfOrNull { key -> normal(key)?.let { stats[it] } }

    /**
     * `stats: [{id, installSec, bootSec, fpsWeak, fpsAll, n}]`; weak phones' frames win over all phones'.
     * A row needs a time — the install or the start; frames alone say nothing about the wait.
     */
    fun parseStats(body: JsonElement?): Map<String, PackStat> {
        val rows = ((body as? JsonObject)?.get("stats") as? JsonArray) ?: return emptyMap()
        val out = HashMap<String, PackStat>()
        for (row in rows) {
            val obj = row as? JsonObject ?: continue
            fun number(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
            val id = normal(((obj["id"] as? JsonPrimitive)?.takeIf { it.isString })?.contentOrNull) ?: continue
            val boot = number("bootSec")?.toInt() ?: 0
            val install = number("installSec")?.toInt() ?: 0
            if (boot == 0 && install == 0) continue
            val fps = number("fpsWeak") ?: number("fpsAll")
            out[id] = PackStat(id, boot, fps?.toInt() ?: 0, number("n")?.toInt() ?: 0, install)
        }
        return out
    }

    fun encodeStats(all: Map<String, PackStat>): String =
        all.values.sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.bootSec}|${it.fps}|${it.samples}|${it.installSec}" }

    fun decodeStats(raw: String?): Map<String, PackStat> = raw.orEmpty().lineSequence().mapNotNull { line ->
        val part = line.split('|')
        val boot = part.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val fps = part.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
        val id = normal(part[0]) ?: return@mapNotNull null
        PackStat(id, boot, fps, part.getOrNull(3)?.toIntOrNull() ?: 0, part.getOrNull(4)?.toIntOrNull() ?: 0)
    }.associateBy { it.id }

    fun hides(vararg keys: String?): Boolean = hides(blocked, *keys)

    fun hides(ids: Set<String>, vararg keys: String?): Boolean {
        if (ids.isEmpty()) return false
        return keys.any { key -> normal(key)?.let { it in ids } == true }
    }

    fun <T> filter(items: List<T>, keys: (T) -> List<String?>): List<T> {
        val ids = blocked
        if (ids.isEmpty()) return items
        return items.filterNot { hides(ids, *keys(it).toTypedArray()) }
    }

    fun due(cache: PhoneBlockedCache?, nowMs: Long): Boolean =
        cache == null || nowMs - cache.fetchedAtMs !in 0 until REFRESH_MS

    /**
     * Asks the server at most once an hour. A network error keeps the last list; a 404 or an
     * empty answer means nothing is hidden. Returns the cache to keep.
     */
    suspend fun refresh(cache: PhoneBlockedCache?, nowMs: Long, fetch: suspend () -> Set<String>): PhoneBlockedCache? {
        if (!due(cache, nowMs)) return cache
        return try {
            PhoneBlockedCache(fetch(), nowMs)
        } catch (missing: HttpError) {
            if (missing.code == 404) PhoneBlockedCache(emptySet(), nowMs) else cache
        } catch (offline: IOException) {
            cache
        }
    }

    fun encode(ids: Set<String>): String = ids.sorted().joinToString("\n")

    fun decode(raw: String?): Set<String> =
        raw.orEmpty().lineSequence().mapNotNull { normal(it) }.toSet()

    private fun normal(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
