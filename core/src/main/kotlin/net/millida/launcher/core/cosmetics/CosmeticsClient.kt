package net.millida.launcher.core.cosmetics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.millida.launcher.core.account.MillidaAuth
import java.io.IOException
import java.net.URLEncoder

/** Geometry is served to signed-in players only and under a daily cap, so it is asked for what is worn or tried on. */
class CosmeticModelFile(
    val geometry: JsonElement,
    val geometrySlim: JsonElement?,
    val animations: JsonElement?,
)

class OwnedCosmetics(val codes: List<String>, val variants: Map<String, List<String>>?)

/** The same endpoints as the desktop launcher (src/lib/gameProfile.ts), so both wardrobes dress one account. */
class CosmeticsClient(private val auth: MillidaAuth) {

    /** Open to guests: the catalogue is shown before an account exists. */
    suspend fun catalog(): CosmeticCatalog =
        CosmeticCatalog.of(CosmeticCatalog.parseItems(auth.publicCall("/cosmetics/catalog", "GET", null)))

    /** Rights only; free items are everybody's and never listed. */
    suspend fun owned(): OwnedCosmetics {
        val answer = auth.authorized("/cosmetics/owned", "GET", null)
        val codes = (answer["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val variants = (answer["variants"] as? JsonObject)?.mapValues { (_, value) ->
            (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        }
        return OwnedCosmetics(codes, variants)
    }

    /** The address answers for many players at once; the player asks for their own game uuid only. */
    suspend fun equipped(uuid: String): List<WornCosmetic> {
        if (uuid.isBlank()) return emptyList()
        val answer = auth.publicCall(
            "/cosmetics/equipped",
            "POST",
            buildJsonObject { putJsonArray("players") { add(uuid) } },
        ) as? JsonObject ?: throw IOException("сервер вернул не тот ответ — обнови гардероб")
        val players = answer["players"] as? JsonObject ?: return emptyList()
        val wanted = uuid.replace("-", "")
        val key = players.keys.firstOrNull { it.replace("-", "") == wanted } ?: return emptyList()
        return (players[key] as? JsonArray).orEmpty().mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val id = item.text("id")
            val slot = item.text("slot")
            if (id.isEmpty() || slot.isEmpty()) null else WornCosmetic(id, slot, item.text("variant"))
        }
    }

    /** The server overwrites the whole set, so everything worn is sent, never a difference. */
    suspend fun wear(items: List<WornCosmetic>) {
        val answer = auth.authorized(
            "/cosmetics/wardrobe",
            "POST",
            buildJsonObject {
                putJsonArray("items") {
                    for (item in items) {
                        addJsonObject {
                            put("id", item.id)
                            put("slot", item.slot)
                            if (item.variant.isNotEmpty()) put("variant", item.variant)
                        }
                    }
                }
            },
        )
        if ((answer["ok"] as? JsonPrimitive)?.content == "false") {
            throw IOException("сервер не принял набор — обнови гардероб и попробуй ещё раз")
        }
    }

    suspend fun model(modelId: String): CosmeticModelFile {
        val path = "/cosmetics/models/" + URLEncoder.encode(modelId, "UTF-8").replace("+", "%20")
        val answer = auth.authorized(path, "GET", null)
        val geometry = answer["geometry"]?.takeIf { it !is JsonNull }
            ?: throw IOException("у вещи нет модели — обнови гардероб")
        return CosmeticModelFile(
            geometry = geometry,
            geometrySlim = answer["geometrySlim"]?.takeIf { it !is JsonNull },
            animations = answer["animations"]?.takeIf { it !is JsonNull },
        )
    }
}
