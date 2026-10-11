package net.millida.launcher.core

import net.millida.launcher.core.loader.LoaderKind

data class DeviceMemory(val totalMb: Int, val availableMb: Int)

data class MemoryPlan(
    val heapMb: Int,
    val warning: String?,
    val canLaunch: Boolean,
)

data class LowMemoryNotice(val nominalGb: Int, val text: String)

object MemoryTuning {
    /**
     * A "4 GB" phone reports 3.6-3.8 GiB: the kernel and the modem keep their
     * share before Android counts anything. The line sits below that, so a
     * phone sold as 4 GB is not warned and a 3 GB one always is.
     */
    const val LOW_MEMORY_LINE_MB = 3584

    fun lowMemory(device: DeviceMemory): LowMemoryNotice? {
        if (device.totalMb <= 0 || device.totalMb >= LOW_MEMORY_LINE_MB) return null
        val nominal = ((device.totalMb + 1023) / 1024).coerceAtLeast(1)
        return LowMemoryNotice(
            nominalGb = nominal,
            text = "На телефоне $nominal ГБ памяти — игра может вылетать. " +
                "Лучше играть на ванили или Fabric с дальностью прорисовки 6.",
        )
    }

    private const val SYSTEM_RESERVE_MB = 900
    private const val JVM_OVERHEAD_MB = 260
    private const val FLOOR_MB = 512

    // availMem leaves out the cached apps the system drops for a foreground
    // game, so a phone reports 1.6 GB free while it has far more to give; only
    // a phone below this line cannot hold the smallest heap and the machine.
    private const val REFUSE_BELOW_MB = 1024

    /** The most heap the game could ever get on this phone, with nothing else running. */
    fun heapCeilingMb(totalMb: Int): Int = (totalMb - SYSTEM_RESERVE_MB - JVM_OVERHEAD_MB).coerceAtLeast(0)

    /** The top of the memory slider: the phone's own ceiling, on the slider's step. */
    fun settingCeilingMb(totalMb: Int): Int =
        (heapCeilingMb(totalMb) / SETTING_STEP_MB * SETTING_STEP_MB).coerceAtLeast(SETTING_CEILING_FLOOR_MB)

    const val SETTING_STEP_MB = 128
    private const val SETTING_CEILING_FLOOR_MB = 1024

    /**
     * A pack with its own launch keeps its mods in its libraries, so the
     * `mods/` count says nothing about it; the minimum its author wrote into
     * the launch description stands instead.
     */
    fun plan(device: DeviceMemory, loader: LoaderKind, modCount: Int, packMinMb: Int = 0): MemoryPlan {
        val usable = (device.availableMb - SYSTEM_RESERVE_MB - JVM_OVERHEAD_MB).coerceAtLeast(0)
        val vanilla = loader == LoaderKind.VANILLA && modCount == 0
        val byMods = when {
            vanilla -> 1024
            modCount <= 20 -> 1536
            modCount <= 60 -> 2048
            else -> 2560
        }
        val scaled = if (vanilla) byMods else scaledForDevice(byMods, device.totalMb)
        val wanted = maxOf(scaled, packMinMb)
        // A pack's own minimum is what its mods need to start at all: below it the game dies
        // of OutOfMemoryError for sure, while availMem undercounts what the system frees for
        // a foreground game. The phone's own ceiling is the only cap on it.
        val packFits = packMinMb > 0 && packMinMb <= heapCeilingMb(device.totalMb)
        val heap = if (packFits) maxOf(minOf(wanted, usable), packMinMb) else minOf(wanted, usable)

        if (device.availableMb < REFUSE_BELOW_MB) {
            return MemoryPlan(
                heapMb = FLOOR_MB,
                warning = "Свободно всего ${device.availableMb} МБ — игру закроет система. " +
                    "Закрой другие приложения и попробуй снова.",
                canLaunch = false,
            )
        }

        val warning = if (packFits && usable < packMinMb) {
            "Сборке нужно $packMinMb МБ, а игре сейчас достаётся $usable МБ: закрой другие приложения, иначе система может закрыть игру."
        } else if (heap < wanted) {
            "Памяти хватает только на $heap МБ вместо $wanted МБ: на больших мирах игра может закрыться."
        } else {
            null
        }
        return MemoryPlan(heapMb = heap.coerceAtLeast(FLOOR_MB), warning = warning, canLaunch = true)
    }

    /**
     * The tiers were measured on 8 GB phones. A bigger phone gets the same
     * share of its memory, up to twice the tier at 16 GB; a phone at 8 GB or
     * below keeps the tier unchanged. The result never passes [SHARE_CAP_PERCENT]
     * of the phone: the driver, the renderer's buffers and the JVM's own native
     * memory live outside the heap, and Android kills the game long before the
     * heap is full when they have no room.
     */
    fun scaledForDevice(tierMb: Int, totalMb: Int): Int {
        if (totalMb <= SCALE_FROM_MB) return tierMb
        val ratio = totalMb.coerceAtMost(SCALE_UNTIL_MB).toLong()
        val grown = (tierMb.toLong() * ratio / SCALE_FROM_MB).toInt() / SCALE_STEP_MB * SCALE_STEP_MB
        val cap = (totalMb.toLong() * SHARE_CAP_PERCENT / 100).toInt()
        return grown.coerceAtMost(cap).coerceAtLeast(tierMb)
    }

    private const val SCALE_FROM_MB = 8192
    private const val SCALE_UNTIL_MB = 16384
    private const val SCALE_STEP_MB = 128
    private const val SHARE_CAP_PERCENT = 40

    fun gcFlags(heapMb: Int): List<String> = buildList {
        add("-XX:+UnlockExperimentalVMOptions")
        add("-XX:+UseSerialGC")
        add("-XX:TieredStopAtLevel=4")
        add("-XX:-OmitStackTraceInFastThrow")
        add("-Dfml.ignorePatchDiscrepancies=true")
        add("-Dfml.ignoreInvalidMinecraftCertificates=true")
        if (heapMb <= 1024) add("-XX:MaxMetaspaceSize=256M") else add("-XX:MaxMetaspaceSize=512M")
    }
}
