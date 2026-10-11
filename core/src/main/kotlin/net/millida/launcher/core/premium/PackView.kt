package net.millida.launcher.core.premium

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import net.millida.launcher.core.packs.PhoneFit
import java.net.URI

/** `GET /catalog/packs/:slug` as the pack page reads it: art, video, frames and the size of the client. */
data class PackView(
    val slug: String,
    val title: String = "",
    val summary: String = "",
    val game: String = "",
    val loader: String = "",
    val cover: String? = null,
    val banner: String? = null,
    val video: String? = null,
    val gallery: List<String> = emptyList(),
    val author: String? = null,
    val accessRequired: Boolean = false,
    val clientBytes: Long = 0,
    val ownLaunch: Boolean = false,
) {
    val art: String? get() = banner ?: cover

    /** The page's frames: the gallery without repeats and without the art already on top. */
    val frames: List<String> get() = gallery.filter { it != art }.distinct()

    companion object {
        fun parse(body: JsonElement?, slug: String): PackView {
            val root = body as? JsonObject ?: return PackView(slug)
            val client = (root["files"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.text("side") == "client" }
            return PackView(
                slug = root.text("slug").ifEmpty { slug },
                title = root.text("title"),
                summary = root.text("summary"),
                game = root.text("game"),
                loader = root.text("loader"),
                cover = root.https("cover"),
                banner = root.https("banner"),
                video = root.https("video"),
                gallery = (root["gallery"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(::isHttps) },
                author = root.text("author").ifEmpty { null },
                accessRequired = (root["accessRequired"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
                clientBytes = (client?.get("size") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0,
                ownLaunch = PhoneFit.ownLaunchIn(root),
            )
        }

        /** Images and the video are loaded or opened from the network, so only a plain https address passes. */
        fun isHttps(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return "https".equals(uri.scheme, ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        }

        private fun JsonObject.https(key: String): String? = text(key).takeIf { it.isNotEmpty() && isHttps(it) }

        private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
