package net.millida.launcher.core.skins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.MojangJson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class MsCape(
    val id: String,
    val alias: String,
    val url: String,
    val active: Boolean,
)

data class MsProfile(
    val uuid: String,
    val nick: String,
    val skinUrl: String,
    val slim: Boolean,
    val capes: List<MsCape>,
) {
    val activeCape: MsCape? get() = capes.firstOrNull { it.active }
}

/**
 * The licensed account's own skin and capes. Needs the Minecraft session token
 * that MicrosoftAuth already produced - there is no second sign-in here, the
 * caller passes the token of the account the player is looking at.
 */
class MinecraftServicesClient(
    private val client: OkHttpClient = Downloader.shared(),
    private val base: String = "https://api.minecraftservices.com/minecraft/profile",
) {
    suspend fun profile(token: String): MsProfile = withContext(Dispatchers.IO) {
        val json = call(Request.Builder().url(base).bearer(token).get().build())
        parseProfile(json)
    }

    /**
     * The texture goes as bytes, not as a link: upload by URL only works for
     * addresses Mojang can reach itself and silently left the old skin in place
     * when it could not. Bytes are always there.
     */
    suspend fun uploadSkin(token: String, png: ByteArray, slim: Boolean): MsProfile =
        withContext(Dispatchers.IO) {
            TexturePng.validate(TextureKind.SKIN, png)
            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("variant", if (slim) "slim" else "classic")
                .addFormDataPart(
                    "file",
                    "skin.png",
                    png.toRequestBody("image/png".toMediaType()),
                )
                .build()
            val json = call(Request.Builder().url("$base/skins").bearer(token).post(form).build())
            val profile = parseProfile(json)
            // Mojang answers with the whole profile: without this check
            // "applied" turns into a quiet lie when nothing changed.
            if (profile.skinUrl.isEmpty()) {
                throw TextureError("Mojang принял запрос, но скин на аккаунте не сменился — попробуй ещё раз")
            }
            profile
        }

    /** An empty id takes the cape off; anything else is one of the account's own capes. */
    suspend fun setCape(token: String, capeId: String): Unit = withContext(Dispatchers.IO) {
        val url = "$base/capes/active"
        val request = if (capeId.isEmpty()) {
            Request.Builder().url(url).bearer(token).delete().build()
        } else {
            Request.Builder().url(url).bearer(token)
                .put(
                    MojangJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("capeId" to JsonPrimitive(capeId))))
                        .toRequestBody("application/json".toMediaType())
                )
                .build()
        }
        call(request)
    }

    suspend fun resetSkin(token: String): Unit = withContext(Dispatchers.IO) {
        call(Request.Builder().url("$base/skins/active").bearer(token).delete().build())
    }

    private fun parseProfile(json: JsonElement?): MsProfile {
        val root = json as? JsonObject ?: return MsProfile("", "", "", false, emptyList())
        val capes = (root["capes"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val cape = entry as? JsonObject ?: return@mapNotNull null
            val id = cape.text("id")
            if (id.isEmpty()) return@mapNotNull null
            MsCape(
                id = id,
                alias = cape.text("alias"),
                url = https(cape.text("url")),
                active = cape.text("state").equals("ACTIVE", true),
            )
        }
        val skins = (root["skins"] as? JsonArray).orEmpty()
        val skin = skins.firstOrNull { (it as? JsonObject)?.text("state").equals("ACTIVE", true) }
            ?: skins.firstOrNull()
        val active = skin as? JsonObject
        return MsProfile(
            uuid = root.text("id"),
            nick = root.text("name"),
            skinUrl = https(active?.text("url").orEmpty()),
            slim = active?.text("variant").orEmpty().equals("slim", true),
            capes = capes,
        )
    }

    private fun call(request: Request): JsonElement? {
        val response = try {
            client.newCall(request).execute()
        } catch (failure: IOException) {
            throw TextureError("Minecraft не отвечает — проверь сеть и повтори", failure)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw TextureError(errorText(it.code, request.url.encodedPath))
            if (text.isBlank()) return null
            return runCatching { MojangJson.parseToJsonElement(text) }.getOrNull()
        }
    }

    /** Mojang's own errorMessage is English and names no next step, so the status decides the text. */
    private fun errorText(code: Int, path: String): String = when {
        code == 401 -> "Сессия Microsoft истекла — перезайди в аккаунт"
        code == 404 && path.trimEnd('/') == profilePath -> "На аккаунте нет Minecraft Java"
        code == 429 -> "Mojang просит подождать — повтори через минуту"
        else -> "Mojang ответил $code — повтори позже"
    }

    private val profilePath: String get() = base.toHttpUrlOrNull()?.encodedPath?.trimEnd('/').orEmpty()

    private fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun Request.Builder.bearer(token: String): Request.Builder =
        header("Authorization", "Bearer $token")

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
