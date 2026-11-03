package net.millida.launcher.core

import net.millida.launcher.core.i18n.tr
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
            text = tr("На телефоне {nominal} ГБ памяти — игра может вылетать. ", nominal) +
                tr("Лучше играть на ванили или Fabric с дальностью прорисовки 6."),
        )
    }

    private const val SYSTEM_RESERVE_MB = 900
    private const val JVM_OVERHEAD_MB = 260
    private const val FLOOR_MB = 512

    // availMem leaves out the cached apps the system drops for a foreground game, so a
    // phone reports 1.6 GB free while it has far more to give. Telemetry 09-10.2026 (145k
    // launches): 49% of failed launches were this refusal on availMem alone. Android can
    // free about this share of the phone's memory for a foreground game.
    private const val RECLAIMABLE_PERCENT = 40

    // Only a phone that cannot hold the smallest heap and the system is refused.
    private const val REFUSE_BELOW_MB = 1024

    // Same telemetry: 28% of crashes were the OOM killer (process_died) and the share grows
    // with heap (43% at <=600 and 1000-1500 MB against 56-59% at 1500-4000 MB), so the
    // automatic heap takes at most 28% of the phone, never above 2048 MB.
    private const val AUTO_HEAP_PERCENT = 28
    private const val AUTO_HEAP_MAX_MB = 2048
    private const val AUTO_HEAP_MIN_MB = 1024

    /** What the game can count on: the free memory or the share Android frees for it, the larger. */
    fun reclaimableMb(device: DeviceMemory): Int =
        maxOf(device.availableMb, (device.totalMb.toLong() * RECLAIMABLE_PERCENT / 100).toInt())

    /** The most the automatic heap may take on this phone (a player's own setting ignores it). */
    fun autoHeapCapMb(totalMb: Int): Int =
        if (totalMb <= 0) AUTO_HEAP_MAX_MB
        else (totalMb.toLong() * AUTO_HEAP_PERCENT / 100).toInt().coerceIn(AUTO_HEAP_MIN_MB, AUTO_HEAP_MAX_MB)

    /** The most heap the game could ever get on this phone, with nothing else running. */
    fun heapCeilingMb(totalMb: Int): Int = (totalMb - SYSTEM_RESERVE_MB - JVM_OVERHEAD_MB).coerceAtLeast(0)

    /** The top of the memory slider: the phone's own ceiling, on the slider's step. */
    fun settingCeilingMb(totalMb: Int): Int =
        (heapCeilingMb(totalMb) / SETTING_STEP_MB * SETTING_STEP_MB).coerceAtLeast(SETTING_CEILING_FLOOR_MB)

    /**
     * A 32-bit process has 3 GB of addresses for everything: the heap, the Java machine,
     * the game's native buffers and the graphics driver. A heap above this leaves the rest
     * no room, and the machine refuses to start or dies on the first large allocation.
     */
    const val NARROW_HEAP_MAX_MB = 768

    fun fitAddressSpace(heapMb: Int, narrow: Boolean): Int = if (narrow) heapMb.coerceAtMost(NARROW_HEAP_MAX_MB) else heapMb

    // Telemetry 19-26.10.2026 (low_memory, 455 installs): the system kills the whole game process,
    // not the heap, on a phone of up to 6 GB: 9.0% of starts under 3.5 GB, 3.9% at 3.5-5.5 GB
    // against 0.4-0.9% above, and 59% of those installs were such phones. Heap at 60% of the
    // phone killed 2.1% of starts, at under 30% 0.7-1.3%; a pack asking 4096 MB on 5.5 GB
    // phones crashed 36% of starts. So a heap a build or a saved setting asks for stops at this
    // share of the phone; the driver, the renderer and the JVM live outside the heap.
    const val SMALL_PHONE_MAX_MB = 6144
    private const val SMALL_PHONE_HEAP_PERCENT = 40
    private const val SMALL_PHONE_HEAP_FLOOR_MB = 1024

    fun fitSmallPhone(heapMb: Int, totalMb: Int): Int {
        if (totalMb <= 0 || totalMb > SMALL_PHONE_MAX_MB) return heapMb
        val cap = (totalMb.toLong() * SMALL_PHONE_HEAP_PERCENT / 100).toInt()
            .coerceAtLeast(SMALL_PHONE_HEAP_FLOOR_MB) / SETTING_STEP_MB * SETTING_STEP_MB
        return heapMb.coerceAtMost(cap)
    }

    // Redmi Note 13 (8 GB), OneBlock 27.10.2026: the pack's 4096 MB pushed 2.2 GB of the
    // system into swap, 2560 MB left the game without swap at the same FPS and never ran
    // out of heap. A desktop minimum is cut to this share of the phone, never below 2560.
    private const val PACK_HEAP_PERCENT = 32
    private const val PACK_HEAP_FLOOR_MB = 2560

    fun fitPackMinimum(packMinMb: Int, totalMb: Int): Int {
        if (packMinMb <= 0 || totalMb <= 0) return packMinMb
        val cap = ((totalMb.toLong() * PACK_HEAP_PERCENT / 100).toInt() / SETTING_STEP_MB * SETTING_STEP_MB)
            .coerceAtLeast(PACK_HEAP_FLOOR_MB)
        return packMinMb.coerceAtMost(cap)
    }

    const val SETTING_STEP_MB = 128
    private const val SETTING_CEILING_FLOOR_MB = 1024

    /**
     * A pack with its own launch keeps its mods in its libraries, so the
     * `mods/` count says nothing about it; the minimum its author wrote into
     * the launch description stands instead.
     */
    fun plan(device: DeviceMemory, loader: LoaderKind, modCount: Int, packMinMb: Int = 0): MemoryPlan {
        val packMinMb = fitPackMinimum(packMinMb, device.totalMb)
        val reclaimable = reclaimableMb(device)
        val usable = (reclaimable - SYSTEM_RESERVE_MB - JVM_OVERHEAD_MB).coerceAtLeast(0)
        val vanilla = loader == LoaderKind.VANILLA && modCount == 0
        val byMods = when {
            vanilla -> 1024
            modCount <= 20 -> 1536
            modCount <= 60 -> 2048
            else -> 2560
        }
        val scaled = if (vanilla) byMods else scaledForDevice(byMods, device.totalMb)
        val autoWanted = minOf(scaled, autoHeapCapMb(device.totalMb))
        val wanted = maxOf(autoWanted, packMinMb)
        // A pack's own minimum is what its mods need to start at all: below it the game dies
        // of OutOfMemoryError for sure, while availMem undercounts what the system frees for
        // a foreground game. The phone's own ceiling is the only cap on it.
        val packFits = packMinMb > 0 && packMinMb <= heapCeilingMb(device.totalMb)
        val heap = if (packFits) maxOf(minOf(wanted, usable), packMinMb) else minOf(wanted, usable)

        if (reclaimable < REFUSE_BELOW_MB) {
            return MemoryPlan(
                heapMb = FLOOR_MB,
                warning = tr("Свободно всего {availableMb} МБ — игру закроет система. ", device.availableMb) +
                    tr("Закрой другие приложения и попробуй снова."),
                canLaunch = false,
            )
        }

        val warning = if (packFits && usable < packMinMb) {
            tr("Сборке нужно {packMinMb} МБ, а игре сейчас достаётся {usable} МБ: закрой другие приложения, иначе система может закрыть игру.", packMinMb, usable)
        } else if (heap < wanted) {
            tr("Памяти хватает только на {heap} МБ вместо {wanted} МБ: на больших мирах игра может закрыться.", heap, wanted)
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
