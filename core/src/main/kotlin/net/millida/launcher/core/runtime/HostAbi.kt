package net.millida.launcher.core.runtime

object HostAbi {
    const val GAME = "arm64-v8a"

    fun primary(supportedAbis: List<String>): String = supportedAbis.firstOrNull().orEmpty()

    fun describe(supportedAbis: List<String>): String {
        val primary = primary(supportedAbis)
        if (primary == GAME || GAME !in supportedAbis) return primary
        return "$primary, $GAME через перевод"
    }

    fun launchProblem(supportedAbis: List<String>): String? {
        val primary = primary(supportedAbis)
        if (primary == GAME) return null
        return "Игра запускается только на процессоре ARM64, а это устройство — $primary " +
            "(похоже на эмулятор на компьютере): Java из пакета рантайма на нём не загружается. " +
            "Запусти сборку на телефоне или в лаунчере Millida для ПК."
    }
}
