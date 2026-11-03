package net.millida.launcher.core.runtime

import net.millida.launcher.core.i18n.tr

object HostAbi {
    const val ARM64 = "arm64-v8a"

    /** A phone whose system is 32-bit reports only this, even on a 64-bit processor. */
    const val ARM32 = "armeabi-v7a"

    private val GAME = setOf(ARM64, ARM32)

    fun primary(supportedAbis: List<String>): String = supportedAbis.firstOrNull().orEmpty()

    /** The ABI the game runs as: the phone's own one, never one it only translates. */
    fun game(supportedAbis: List<String>): String? = primary(supportedAbis).takeIf { it in GAME }

    fun describe(supportedAbis: List<String>): String {
        val primary = primary(supportedAbis)
        if (primary in GAME || ARM64 !in supportedAbis) return primary
        return tr("{primary}, {ARM64} через перевод", primary, ARM64)
    }

    fun launchProblem(supportedAbis: List<String>): String? {
        if (game(supportedAbis) != null) return null
        return tr("Игра запускается только на процессоре ARM, а это устройство — {0} (похоже на эмулятор на компьютере): Java из пакета рантайма на нём не загружается. ", primary(supportedAbis)) +
            tr("Запусти сборку на телефоне или в лаунчере Millida для ПК.")
    }

    /** Where the system keeps its own libraries for a process of this ABI. */
    fun systemLibraryFolder(abi: String): String = if (abi == ARM32) "lib" else "lib64"
}
