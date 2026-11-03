package net.millida.launcher.core.packs

import net.millida.launcher.core.i18n.tr
import java.io.IOException

/**
 * Asked before a pack downloads [bytes]. The phone decides whether that needs
 * the player's word (a metered network) and may hold the install until it has
 * it; false stops the install before anything is written.
 */
fun interface PackDownloadGate {
    suspend fun allow(bytes: Long, title: String): Boolean

    companion object {
        val OPEN = PackDownloadGate { _, _ -> true }
    }
}

class PackDownloadDeclined : IOException(tr("Установка отменена: сборка не скачана по мобильной сети"))
