package net.millida.launcher.core.cabinet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import java.io.IOException

const val MILLIDA_SITE = "https://millida.net"

/** Anything the cabinet screens can show the player as-is. */
open class CabinetError(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A lapsed session is not a failure to report as one: the screen has to offer
 * the sign-in button, so it needs a verdict it can tell apart from a real error.
 */
class SignInRequired(cause: Throwable? = null) : CabinetError("Нужен вход в Millida", cause)

internal suspend fun MillidaAuth.cabinetElement(path: String): JsonElement = try {
    authorizedElement(path, "GET", null)
} catch (expired: Unauthorized) {
    throw SignInRequired(expired)
} catch (error: IOException) {
    throw CabinetError(error.message ?: "Не удалось загрузить данные кабинета", error)
}

internal suspend fun MillidaAuth.cabinetObject(path: String): JsonObject =
    cabinetElement(path) as? JsonObject
        ?: throw CabinetError("Сервер ответил не тем, что ожидалось")

/**
 * An empty answer is a normal answer here: a fresh account has no operations,
 * no unlocked awards and no posts, and none of that is worth an error screen.
 */
internal fun JsonElement?.itemsArray(vararg keys: String): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> keys.firstNotNullOfOrNull { key -> this[key] as? JsonArray } ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}

internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

internal fun JsonObject.text(key: String): String = when (val value = this[key]) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> ""
}

internal fun JsonObject.number(key: String): Long = text(key).toLongOrNull()
    ?: text(key).toDoubleOrNull()?.toLong()
    ?: 0

internal fun JsonObject.count(key: String): Int = number(key).toInt()

internal fun JsonObject.flag(key: String): Boolean = text(key).toBooleanStrictOrNull() ?: false

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

internal fun JsonObject.child(key: String): JsonObject? = (this[key] as? JsonObject)?.jsonObject
