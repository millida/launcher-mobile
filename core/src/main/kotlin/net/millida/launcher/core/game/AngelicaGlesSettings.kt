package net.millida.launcher.core.game

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.atomicWriteText
import java.io.File

/**
 * Angelica's own video options, as it reads them from the game folder, put on
 * the values the phone's GLES 3.2 can draw with. Only the keys GLES cannot
 * serve are changed; every other choice of the player stays as it was.
 */
object AngelicaGlesSettings {

    const val FILE_NAME = "angelica-options.json"

    /*
     * INDIRECT falls back to DIRECT without GL 4.3, and DIRECT issues
     * glMultiDrawElementsBaseVertex, which GLES 3.2 does not have: terrain is never
     * drawn. INDIVIDUAL loops over glDrawElementsBaseVertex, which is core in ES 3.2.
     */
    val overrides: Map<String, Map<String, JsonPrimitive>> = mapOf(
        "advanced" to mapOf("multi_draw_mode" to JsonPrimitive("INDIVIDUAL")),
        "performance" to mapOf("use_no_error_g_l_context" to JsonPrimitive(false)),
    )

    private val json = Json { prettyPrint = true }

    fun apply(gameDir: File): Boolean {
        val file = File(gameDir, FILE_NAME)
        val existing = read(file)
        val merged = LinkedHashMap<String, JsonElement>(existing)
        overrides.forEach { (section, values) ->
            val current = existing[section] as? JsonObject ?: JsonObject(emptyMap())
            merged[section] = JsonObject(LinkedHashMap<String, JsonElement>(current).apply { putAll(values) })
        }
        val result = JsonObject(merged)
        if (file.isFile && result == existing) return false
        file.atomicWriteText(json.encodeToString(JsonObject.serializer(), result))
        return true
    }

    /** A file Angelica cannot parse is replaced by its defaults anyway, so it counts as empty. */
    private fun read(file: File): JsonObject {
        if (!file.isFile) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(file.readText()) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: SerializationException) {
            JsonObject(emptyMap())
        }
    }
}
