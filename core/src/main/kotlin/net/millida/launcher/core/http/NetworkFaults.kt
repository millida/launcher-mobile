package net.millida.launcher.core.http

import java.net.UnknownHostException

/** One wording for "the name did not resolve", so every screen says the same thing. */
object NetworkFaults {

    fun nameLookupAdvice(subject: String): String =
        "Не удалось определить адрес $subject. Проверь интернет, " +
            "попробуй другой Wi-Fi или мобильный интернет, либо включи VPN."

    fun unknownHost(error: Throwable?): UnknownHostException? {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is UnknownHostException) return current
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return null
    }

    private const val MAX_CAUSE_DEPTH = 8
}
