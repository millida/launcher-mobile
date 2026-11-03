package net.millida.launcher.core.http

import java.net.UnknownHostException

import net.millida.launcher.core.i18n.tr
/** One wording for "the name did not resolve", so every screen says the same thing. */
object NetworkFaults {

    fun hostLookupAdvice(host: String): String = tr("Не удалось определить адрес {host}.", host) + " " + advice()

    fun microsoftLookupAdvice(host: String): String = tr("Не удалось определить адрес сервера входа Microsoft ({host}).", host) + " " + advice()

    fun updatesLookupAdvice(): String = tr("Не удалось определить адрес канала обновлений.") + " " + advice()

    private fun advice(): String = tr("Проверь интернет, попробуй другой Wi-Fi или мобильный интернет, либо включи VPN.")

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
