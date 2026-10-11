package net.millida.launcher.core.telemetry

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Event names of the desktop launcher (src/lib/telemetry.ts): the server keeps a list and drops the rest. */
object TelemetryType {
    const val APP_START = "app_start"
    const val APP_EXIT = "app_exit"
    const val GAME_LAUNCH = "game_launch"
    const val GAME_EXIT = "game_exit"
    const val GAME_CRASH = "game_crash"
    const val BUILD_CREATE = "build_create"
    const val BUILD_IMPORT = "build_import"
    const val BUILD_DELETE = "build_delete"
    const val CONTENT_INSTALL = "content_install"
    const val CONTENT_UPDATE = "content_update"
    const val MODPACK_INSTALL = "modpack_install"
    const val SCREEN_VIEW = "screen_view"
    const val SERVER_JOIN = "server_join"
    const val ERROR = "error"
}

enum class BeatState(val wire: String) { IDLE("idle"), PLAYING("playing") }

data class TelemetryEvent(
    val type: String,
    val data: Map<String, JsonPrimitive> = emptyMap(),
    val durationMs: Long? = null,
    val ok: Boolean? = null,
    val at: Long,
    val appVersion: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        if (data.isNotEmpty()) put("data", JsonObject(data))
        durationMs?.let { put("durationMs", it.coerceAtLeast(0)) }
        ok?.let { put("ok", it) }
        put("at", at)
        if (appVersion.isNotEmpty()) put("appVersion", appVersion)
    }

    companion object {
        fun fromJson(json: JsonElement): TelemetryEvent? {
            val obj = json as? JsonObject ?: return null
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: return null
            val data = (obj["data"] as? JsonObject)
                ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it } }
                ?.toMap()
                .orEmpty()
            return TelemetryEvent(
                type = type,
                data = data,
                durationMs = (obj["durationMs"] as? JsonPrimitive)?.longOrNull,
                ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull,
                at = at,
                appVersion = (obj["appVersion"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
    }
}

/**
 * The `device` block of a batch, the same fields the desktop sends plus what
 * only a phone has: who made it, which model and which chip platform.
 */
data class TelemetryDevice(
    val installId: String,
    val sessionId: String,
    val appVersion: String,
    val osVersion: String? = null,
    val arch: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val cpu: String? = null,
    val cpuCores: Int? = null,
    val cpuThreads: Int? = null,
    val ramMb: Int? = null,
    val gpu: String? = null,
    val screen: String? = null,
    val deviceVendor: String? = null,
    val deviceModel: String? = null,
    val soc: String? = null,
    val buildsCount: Int? = null,
    val modsCount: Int? = null,
    val themeMode: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("installId", installId)
        put("sessionId", sessionId)
        put("os", OS)
        put("appVersion", appVersion)
        text("osVersion", osVersion)
        text("arch", arch)
        text("locale", locale)
        text("timezone", timezone)
        text("cpu", cpu)
        count("cpuCores", cpuCores)
        count("cpuThreads", cpuThreads)
        count("ramMb", ramMb)
        text("gpu", gpu)
        text("screen", screen)
        text("deviceVendor", deviceVendor)
        text("deviceModel", deviceModel)
        text("soc", soc)
        buildsCount?.let { put("buildsCount", it.coerceAtLeast(0)) }
        modsCount?.let { put("modsCount", it.coerceAtLeast(0)) }
        // The desktop reports its look as a theme pack; the phone has only the standard one.
        put("themePack", "")
        themeMode?.let { put("themeMode", it) }
    }

    private fun JsonObjectBuilder.text(key: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }

    private fun JsonObjectBuilder.count(key: String, value: Int?) {
        value?.takeIf { it > 0 }?.let { put(key, it) }
    }

    companion object {
        const val OS = "android"
    }
}

object TelemetryPayload {
    const val MAX_BATCH = 100

    fun batch(device: TelemetryDevice, events: List<TelemetryEvent>): JsonObject = buildJsonObject {
        put("device", device.toJson())
        put("events", buildJsonArray { events.take(MAX_BATCH).forEach { add(it.toJson()) } })
    }

    /** The launcher heartbeat, alone for a guest or inside the presence beat for a signed-in player. */
    fun beat(device: TelemetryDevice, state: BeatState, build: String? = null, mc: String? = null, server: String? = null): JsonObject =
        buildJsonObject {
            put("installId", device.installId)
            put("status", state.wire)
            put("os", TelemetryDevice.OS)
            put("appVersion", device.appVersion)
            device.locale?.let { put("locale", it) }
            device.timezone?.let { put("timezone", it) }
            if (state == BeatState.PLAYING) {
                build?.takeIf { it.isNotBlank() }?.let { put("build", it.take(64)) }
                mc?.takeIf { it.isNotBlank() }?.let { put("mc", it.take(24)) }
                server?.takeIf { it.isNotBlank() }?.let { put("server", it.take(64)) }
            }
        }
}

/** Builds event data from plain values, dropping blanks the way the desktop leaves out empty fields. */
class EventData {
    private val values = LinkedHashMap<String, JsonPrimitive>()

    fun put(key: String, value: String?): EventData {
        val text = value?.let(TelemetryPrivacy::scrub)?.trim()
        if (!text.isNullOrEmpty()) values[key] = JsonPrimitive(text)
        return this
    }

    fun put(key: String, value: Number?): EventData {
        if (value != null) values[key] = JsonPrimitive(value)
        return this
    }

    fun put(key: String, value: Boolean?): EventData {
        if (value != null) values[key] = JsonPrimitive(value)
        return this
    }

    fun putAll(other: Map<String, JsonPrimitive>): EventData {
        values.putAll(other)
        return this
    }

    fun build(): Map<String, JsonPrimitive> = values.toMap()
}
