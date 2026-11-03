package net.millida.launcher.core.hosting

import kotlinx.coroutines.CancellationException
import net.millida.launcher.core.account.Unauthorized
import java.io.IOException

import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trSource
/**
 * Anything the hosting client refuses or fails to do arrives as this, already
 * worded for the player. Nothing here is swallowed: a caller that ignores it
 * would be telling the player the server changed when it did not.
 */
class HostingError(message: String, cause: Throwable? = null) : IOException(message, cause)

private val FALLBACK: String get() = tr("сервис хостинга не ответил, попробуй позже")

private val STATUS_TEXT get() = mapOf(
    400 to tr("запрос не принят — проверь введённые данные"),
    401 to tr("сессия Millida закончилась — войди в аккаунт заново"),
    402 to tr("не хватает средств на балансе — пополни счёт и повтори"),
    403 to tr("Millida отказала в этом действии — не хватает прав на сервере"),
    404 to tr("не найдено — возможно, это уже удалено"),
    405 to tr("действие недоступно — обнови приложение и повтори"),
    408 to tr("сервер не ответил вовремя — повтори попытку"),
    409 to tr("занято — выбери другое значение"),
    410 to tr("ссылка устарела — запроси новую"),
    413 to tr("файл слишком большой — уменьши размер и повтори"),
    415 to tr("такой формат файла не поддерживается"),
    422 to tr("данные заполнены неверно — проверь поля"),
    423 to tr("действие временно заблокировано — повтори позже"),
    429 to tr("слишком много попыток подряд — подожди немного и повтори"),
    500 to tr("сбой на стороне Millida — повтори через минуту"),
    502 to tr("Millida временно недоступна — повтори через минуту"),
    503 to tr("Millida перегружена или обновляется — повтори через минуту"),
    504 to tr("Millida не успела ответить — повтори через минуту"),
)

// MillidaAuth reports a bodyless failure as this sentence, and it is the only
// place the status code survives the call.
private val STATUS_ONLY = Regex("""(?:сервер ответил|http)\s+(\d{3})""", RegexOption.IGNORE_CASE)

private val NOISE = listOf(
    Regex("^unauthorized$", RegexOption.IGNORE_CASE),
    Regex("^forbidden$", RegexOption.IGNORE_CASE),
    Regex("^not found$", RegexOption.IGNORE_CASE),
    Regex("^bad request$", RegexOption.IGNORE_CASE),
    Regex("^internal server error$", RegexOption.IGNORE_CASE),
    Regex("^service unavailable$", RegexOption.IGNORE_CASE),
    Regex("^too many requests$", RegexOption.IGNORE_CASE),
    Regex("^error$", RegexOption.IGNORE_CASE),
    Regex("^throttlerexception.*", RegexOption.IGNORE_CASE),
)

internal fun statusText(code: Int): String = STATUS_TEXT[code] ?: tr("Millida ответила кодом {code} — повтори попытку", code)

internal fun hostingFailure(action: String, error: Throwable): HostingError {
    if (error is HostingError) return error
    if (error is Unauthorized) return HostingError("$action: ${statusText(401)}", error)
    val raw = error.message.orEmpty().trim()
    val probe = trSource(raw)
    val code = STATUS_ONLY.find(probe)?.groupValues?.get(1)?.toIntOrNull()
    val reason = when {
        code != null -> statusText(code)
        raw.isEmpty() || NOISE.any { it.matches(probe) } -> FALLBACK
        else -> raw
    }
    return HostingError("$action: $reason", error)
}

internal inline fun <T> guard(action: String, block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    throw hostingFailure(action, error)
}
