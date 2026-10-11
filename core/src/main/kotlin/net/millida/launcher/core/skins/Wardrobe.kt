package net.millida.launcher.core.skins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import java.io.IOException

/**
 * The skin is what other players see, so it belongs to the account and not to a
 * build: the same wardrobe follows the player between the phone and the desktop
 * launcher. Everything here is the server's own storage - the launcher only
 * shows it and sends what the player picked.
 */
data class GameProfile(
    val uuid: String = "",
    val name: String = "",
    val model: String = "classic",
    val skinUrl: String = "",
    val capeUrl: String = "",
    val accountNick: String = "",
    val publicSlug: String = "",
    val nameConflict: Boolean = false,
) {
    val slim: Boolean get() = model.equals("slim", ignoreCase = true)
}

data class WardrobeItem(
    val id: String,
    val kind: String,
    val name: String,
    val url: String,
    val model: String = "classic",
    val source: String = "",
) {
    val isCape: Boolean get() = kind.equals("cape", ignoreCase = true)
}

data class CapeOffer(
    val id: String,
    val name: String,
    val url: String,
    val rarity: String = "",
    val requirement: String = "",
    val unlocked: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTarget: Int = 0,
    val progressUnit: String = "",
)

data class Reward(
    val code: String,
    val title: String,
    val task: String,
    val hint: String = "",
    val unit: String = "count",
    val goal: Int = 0,
    val progress: Int = 0,
    val done: Boolean = false,
    val claimed: Boolean = false,
    val capeUrl: String = "",
)

class WardrobeClient(
    private val auth: MillidaAuth,
) {
    suspend fun profile(): GameProfile {
        val response = auth.authorized("/launcher/game-profile", "GET", null)
        return GameProfile(
            uuid = response.text("uuid"),
            name = response.text("name"),
            model = response.text("model").ifEmpty { "classic" },
            skinUrl = response.text("skinUrl"),
            capeUrl = response.text("capeUrl"),
            accountNick = response.text("accountNick"),
            publicSlug = response.text("publicSlug"),
            nameConflict = response["nameConflict"]?.jsonPrimitive?.let {
                runCatching { it.boolean }.getOrDefault(false)
            } ?: false,
        )
    }

    /**
     * Sends a texture and makes it current in one call. The picture goes as
     * base64 because that is what the desktop launcher does and what the server
     * expects - one format for both.
     */
    suspend fun applyTexture(
        kind: String,
        pngBase64: String,
        slim: Boolean,
        name: String,
    ): GameProfile {
        val response = auth.authorized(
            "/launcher/game-texture",
            "POST",
            buildJsonObject {
                put("type", kind)
                put("pngBase64", pngBase64)
                put("slim", slim)
                put("name", name)
            },
        )
        return GameProfile(
            model = response.text("model").ifEmpty { if (slim) "slim" else "classic" },
            skinUrl = response.text("skinUrl"),
            capeUrl = response.text("capeUrl"),
        )
    }

    suspend fun wardrobe(): List<WardrobeItem> {
        val response = auth.authorized("/launcher/wardrobe", "GET", null)
        val items = response["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element.jsonObject
            val id = item.text("id")
            if (id.isEmpty()) return@mapNotNull null
            WardrobeItem(
                id = id,
                kind = item.text("kind").ifEmpty { "skin" },
                name = item.text("name"),
                url = item.text("url"),
                model = item.text("model").ifEmpty { "classic" },
                source = item.text("source"),
            )
        }
    }

    suspend fun applyWardrobe(id: String) {
        auth.authorized("/launcher/wardrobe/$id/apply", "POST", buildJsonObject { })
    }

    suspend fun removeWardrobe(id: String) {
        auth.authorized("/launcher/wardrobe/$id", "DELETE", null)
    }

    /**
     * Locked capes come back too: a player should see what there is to earn and
     * how far they are, not an empty screen.
     */
    suspend fun capes(): List<CapeOffer> {
        val element = auth.authorizedElement("/launcher/wardrobe/capes/catalog", "GET", null)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["items"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { entry ->
            val item = entry.jsonObject
            val id = item.text("id")
            if (id.isEmpty()) return@mapNotNull null
            CapeOffer(
                id = id,
                name = item.text("name"),
                url = item.text("url"),
                rarity = item.text("rarity"),
                requirement = item.text("requirement"),
                unlocked = item.flag("unlocked"),
                progressCurrent = item.number("progressCurrent"),
                progressTarget = item.number("progressTarget"),
                progressUnit = item.text("progressUnit"),
            )
        }
    }

    /** Open to guests: the server keeps the live list of Mojang capes anyone may wear. */
    suspend fun mojangCapes(): List<CapeOffer> {
        val element = auth.publicCall("/launcher/wardrobe/capes/mojang", "GET", null, withSession = false)
        val items = (element as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val id = item.text("id")
            val url = item.text("url")
            if (id.isEmpty() || url.isEmpty()) return@mapNotNull null
            CapeOffer(id = id, name = item.text("name").ifEmpty { id }, url = url, unlocked = true)
        }
    }

    suspend fun applyCape(id: String) {
        auth.authorized("/launcher/wardrobe/capes/$id/apply", "POST", buildJsonObject { })
    }

    suspend fun rewards(): List<Reward> {
        val element = auth.authorizedElement("/launcher/rewards", "GET", null)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["items"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { entry -> reward(entry.jsonObject) }
    }

    suspend fun claim(code: String): Reward {
        val answer = auth.authorized("/launcher/rewards/$code/claim", "POST", buildJsonObject { })
        val reward = reward(answer) ?: throw IOException("сервер не вернул награду — обнови список и попробуй ещё раз")
        if (!reward.claimed) throw IOException("плащ «${reward.title.ifEmpty { code }}» не выдан — обнови список и попробуй ещё раз")
        return reward
    }

    private fun reward(item: JsonObject): Reward? {
        val code = item.text("code")
        if (code.isEmpty()) return null
        return Reward(
            code = code,
            title = item.text("title"),
            task = item.text("task"),
            hint = item.text("hint"),
            unit = item.text("unit").ifEmpty { "count" },
            goal = item.number("goal"),
            progress = item.number("progress"),
            done = item.flag("done"),
            claimed = item.flag("claimed"),
            capeUrl = item.text("capeUrl"),
        )
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull().orEmpty()

    private fun JsonObject.flag(key: String): Boolean =
        this[key]?.jsonPrimitive?.let { runCatching { it.boolean }.getOrDefault(false) } ?: false

    private fun JsonObject.number(key: String): Int =
        this[key]?.jsonPrimitive?.let { runCatching { it.int }.getOrDefault(0) } ?: 0

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
