package net.millida.launcher.core.rubies

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.friends.FriendsErrors
import net.millida.launcher.core.mojang.MojangJson
import java.io.IOException
import java.net.URLEncoder

@Serializable
data class CreatorSupport(
    val code: String? = null,
    val name: String = "",
    val avatarUrl: String? = null,
    val since: String = "",
)

/** A free chest for the first code ever entered. */
@Serializable
data class CreatorBonus(val chestId: String = "", val tier: String = "")

@Serializable
data class CreatorAnswer(
    val code: String? = null,
    val name: String = "",
    val avatarUrl: String? = null,
    val since: String = "",
    val bonus: CreatorBonus? = null,
) {
    val support: CreatorSupport get() = CreatorSupport(code, name, avatarUrl, since)
}

@Serializable
data class CreatorPublic(val code: String = "", val name: String = "", val avatarUrl: String? = null)

/** `live` is false while the service has no creator-code routes yet: the banner stays, input answers "soon". */
data class CreatorStatus(val live: Boolean, val current: CreatorSupport?)

class CreatorError(message: String, val missingRoute: Boolean, cause: Throwable? = null) : IOException(message, cause)

/**
 * The code lives on the service, not on the phone: purchases made on the site
 * count for the creator too.
 */
class CreatorClient(private val auth: MillidaAuth) {

    suspend fun load(): CreatorStatus = try {
        val support = decode(CreatorSupport.serializer(), auth.authorizedElement(PATH, "GET", null))
        CreatorStatus(live = true, current = support.takeIf { !it.code.isNullOrBlank() })
    } catch (expired: Unauthorized) {
        throw expired
    } catch (error: IOException) {
        if (isMissingRoute(error.message)) CreatorStatus(live = false, current = null) else throw failure(error, live = true)
    } catch (unreadable: SerializationException) {
        throw CreatorError(FALLBACK, missingRoute = false, unreadable)
    }

    suspend fun set(code: String, live: Boolean): CreatorAnswer {
        val trimmed = normalizeCreatorCode(code) ?: throw CreatorError("Введи код автора", missingRoute = false)
        return call(live) { decode(CreatorAnswer.serializer(), auth.authorized(PATH, "POST", buildJsonObject { put("code", trimmed) })) }
    }

    suspend fun clear() {
        call(live = false) { auth.authorized(PATH, "DELETE", null) }
    }

    suspend fun lookup(code: String): CreatorPublic {
        val trimmed = normalizeCreatorCode(code) ?: throw CreatorError("Введи код автора", missingRoute = false)
        val path = "/creator-codes/public/" + URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        return call(live = true) { decode(CreatorPublic.serializer(), auth.publicCall(path, "GET", null, withSession = false)) }
    }

    private inline fun <T> call(live: Boolean, block: () -> T): T = try {
        block()
    } catch (already: CreatorError) {
        throw already
    } catch (expired: Unauthorized) {
        throw expired
    } catch (unreadable: SerializationException) {
        throw CreatorError(FALLBACK, missingRoute = false, unreadable)
    } catch (error: IOException) {
        throw failure(error, live)
    }

    private fun failure(error: IOException, live: Boolean): CreatorError =
        CreatorError(creatorErrorText(error.message, live), missingRoute = isMissingRoute(error.message), cause = error)

    private fun <T> decode(serializer: DeserializationStrategy<T>, element: JsonElement): T =
        MojangJson.decodeFromJsonElement(serializer, element)

    companion object {
        private const val PATH = "/launcher/creator-code"
        const val MAX_CODE = 32
        const val SOON = "Коды авторов скоро заработают"
        const val CREATORS_URL = "https://millida.net/creators?utm_source=launcher&utm_medium=shop&utm_campaign=creator_code"
        private const val FALLBACK = "Не получилось, попробуй позже"
    }
}

private val MISSING_ROUTE = Regex("^(сервер ответил 404|http 404|cannot (get|post|delete)\\b.*|not found)$", RegexOption.IGNORE_CASE)

/** A bare 404 means the address is not there yet; "Код не найден" is the service's own answer and passes through. */
fun isMissingRoute(message: String?): Boolean = MISSING_ROUTE.matches(message.orEmpty().trim().removePrefix("Error:").trim())

/**
 * `live` — the service answered the GET, so a bare 404 on input is an unknown
 * code rather than a missing address.
 */
fun creatorErrorText(message: String?, live: Boolean): String {
    if (isMissingRoute(message)) return if (live) "Код не найден" else CreatorClient.SOON
    return FriendsErrors.text(IOException(message.orEmpty()), "Не получилось, попробуй позже")
}

fun normalizeCreatorCode(raw: String): String? =
    raw.trim().uppercase().takeIf { it.isNotEmpty() && it.length <= CreatorClient.MAX_CODE }
