package net.millida.launcher.core.friends

import net.millida.launcher.core.account.Unauthorized
import java.io.IOException

/** Failure of a friends call, carrying text meant for the player, not a code. */
class FriendsError(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One reading of a failure for the whole friends feature. Without it "нет
 * такого ника" reached the player as "войди в аккаунт" and sent them to fix a
 * sign-in that was fine, while a bare "сервер ответил 404" says neither what
 * happened nor what to do next.
 */
object FriendsErrors {

    private val STATUS_TEXT = mapOf(
        400 to "Запрос не принят — проверь введённые данные",
        401 to "Сессия Millida закончилась — войди в аккаунт заново",
        403 to "Millida отказала в этом действии",
        404 to "Не найдено — возможно, это уже удалено",
        405 to "Действие недоступно — обнови приложение и повтори",
        408 to "Сервер не ответил вовремя — повтори попытку",
        409 to "Занято — выбери другое значение",
        410 to "Ссылка устарела — запроси новую",
        413 to "Файл слишком большой — уменьши размер и повтори",
        415 to "Такой формат файла не поддерживается",
        422 to "Данные заполнены неверно — проверь поля",
        423 to "Действие временно заблокировано — повтори позже",
        500 to "Сбой на стороне Millida — повтори через минуту",
        502 to "Millida временно недоступна — повтори через минуту",
        503 to "Millida перегружена или обновляется — повтори через минуту",
        504 to "Millida не успела ответить — повтори через минуту",
    )

    private const val RATE_LIMIT = "Слишком много попыток подряд — подожди немного и повтори"

    private val THROTTLED = Regex("^throttlerexception", RegexOption.IGNORE_CASE)

    private val NOISE = listOf(
        THROTTLED,
        Regex("^too many requests$", RegexOption.IGNORE_CASE),
        Regex("^unauthorized$", RegexOption.IGNORE_CASE),
        Regex("^forbidden$", RegexOption.IGNORE_CASE),
        Regex("^not found$", RegexOption.IGNORE_CASE),
        Regex("^bad request$", RegexOption.IGNORE_CASE),
        Regex("^internal server error$", RegexOption.IGNORE_CASE),
        Regex("^service unavailable$", RegexOption.IGNORE_CASE),
        Regex("^error$", RegexOption.IGNORE_CASE),
    )

    private val BARE_CODE = Regex("^(http |сервер ответил )\\d{3}$", RegexOption.IGNORE_CASE)

    private val STATUS_IN_TEXT = Regex("(?:http|сервер ответил)\\s+(\\d{3})", RegexOption.IGNORE_CASE)

    private val OFFLINE = Regex(
        "нет связи|unable to resolve host|failed to connect|no address associated|" +
            "connection refused|connection reset|network is unreachable|unknownhostexception",
        RegexOption.IGNORE_CASE,
    )

    private val TIMEOUT = Regex("timed ?out|timeout", RegexOption.IGNORE_CASE)

    fun text(error: Throwable?, fallback: String): String {
        if (error is Unauthorized) return STATUS_TEXT.getValue(401)
        val raw = (error?.message ?: "").trim()
        if (raw.isEmpty()) return fallback
        if (OFFLINE.containsMatchIn(raw)) return "Нет связи с Millida — проверь интернет и повтори"
        if (TIMEOUT.containsMatchIn(raw)) return "Millida не ответила вовремя — повтори попытку"

        val status = STATUS_IN_TEXT.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (status == 429 || THROTTLED.containsMatchIn(raw) || raw.equals("too many requests", true)) {
            return RATE_LIMIT
        }
        if (raw.equals("unauthorized", true)) return STATUS_TEXT.getValue(401)

        val bare = BARE_CODE.matches(raw) || NOISE.any { it.containsMatchIn(raw) }
        // The server explains its own refusals ("Переписка — только с друзьями"):
        // such an answer is already the best text there is, so it wins.
        if (!bare) return raw

        if (status > 0) {
            STATUS_TEXT[status]?.let { return it }
            if (status >= 500) return STATUS_TEXT.getValue(500)
            return "$fallback (код $status)"
        }
        return fallback
    }

    fun wrap(error: Throwable, fallback: String): FriendsError =
        FriendsError(text(error, fallback), error)
}
