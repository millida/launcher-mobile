package net.millida.launcher.core.telemetry

/**
 * Turns what Android reports about the phone into the values the desktop puts
 * in the same fields, so one dashboard reads both.
 */
object DeviceFacts {

    /** «Android 14 (API 34)»: a bare «14» would sit next to the desktop's «11 (26100)» in one chart. */
    fun osVersion(release: String?, sdk: Int): String {
        val name = release?.trim().orEmpty().ifEmpty { "?" }
        return "Android $name (API $sdk)".take(OS_VERSION_MAX)
    }

    /**
     * The readable chip name. Android 12 names the SoC itself; before that only
     * the board and hardware codenames are there, and they still tell chips apart.
     */
    fun chip(socManufacturer: String?, socModel: String?, hardware: String?, board: String?): String? {
        val model = known(socModel)
        if (model != null) {
            val vendor = known(socManufacturer)?.let { chipVendor(it) }
            val name = if (vendor == null || model.startsWith(vendor, ignoreCase = true)) model else "$vendor $model"
            return name.take(CPU_MAX)
        }
        return listOfNotNull(known(hardware), known(board))
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifEmpty { null }
            ?.take(CPU_MAX)
    }

    /** The platform codename («kalama», «mt6789»): the board when the vendor fills it in, else the hardware. */
    fun platform(hardware: String?, board: String?): String? =
        (known(board) ?: known(hardware))?.take(SOC_MAX)

    fun chipVendor(raw: String): String = CHIP_VENDORS[raw.trim().lowercase()] ?: raw.trim()

    /** `/sys/devices/system/cpu/possible`: «0-7», «0-3,4-7», «0». Null when the file says nothing usable. */
    fun cpuCount(possible: String?): Int? {
        val text = possible?.trim().orEmpty()
        if (text.isEmpty()) return null
        var total = 0
        for (part in text.split(',')) {
            val bounds = part.trim().split('-')
            val first = bounds.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val last = if (bounds.size > 1) bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: return null else first
            if (last < first || bounds.size > 2) return null
            total += last - first + 1
        }
        return total.takeIf { it in 1..CPU_LIMIT }
    }

    /** Long side first, so the same phone held either way is one screen: «2400x1080@420». */
    fun screen(width: Int, height: Int, densityDpi: Int): String? {
        if (width <= 0 || height <= 0) return null
        val long = maxOf(width, height)
        val short = minOf(width, height)
        val dpi = if (densityDpi > 0) "@$densityDpi" else ""
        return "${long}x$short$dpi".take(SCREEN_MAX)
    }

    /** «Qualcomm Adreno (TM) 740»: the vendor is kept in front unless the renderer already names it. */
    fun gpu(vendor: String?, renderer: String?): String? {
        val name = renderer?.let { clean(it) }?.ifEmpty { null } ?: return known(vendor)?.take(GPU_MAX)
        val maker = vendor?.let { clean(it) }?.ifEmpty { null }
        val label = if (maker == null || name.contains(maker, ignoreCase = true)) name else "$maker $name"
        return label.take(GPU_MAX)
    }

    fun vendor(manufacturer: String?): String? = known(manufacturer)?.take(VENDOR_MAX)

    fun model(model: String?): String? = known(model)?.take(MODEL_MAX)

    fun arch(abis: List<String>): String? = abis.firstOrNull()?.trim()?.ifEmpty { null }?.take(ARCH_MAX)

    fun locale(languageTag: String?): String? = known(languageTag)?.take(LOCALE_MAX)

    /** The phone's «system» is the desktop's «auto». */
    fun themeMode(mode: String?): String? = when (mode) {
        "dark" -> "dark"
        "light" -> "light"
        "system" -> "auto"
        else -> null
    }

    private fun known(value: String?): String? {
        val text = value?.let { clean(it) }.orEmpty()
        return text.takeUnless { it.isEmpty() || it.lowercase() in UNKNOWN }
    }

    private fun clean(value: String): String = value.replace(SPACES, " ").trim()

    private val SPACES = Regex("""[\s\u0000-\u001F\u007F]+""")
    private val UNKNOWN = setOf("unknown", "null", "n/a", "0")
    private val CHIP_VENDORS = mapOf(
        "qti" to "Qualcomm",
        "qcom" to "Qualcomm",
        "qualcomm" to "Qualcomm",
        "qualcomm technologies, inc" to "Qualcomm",
        "mediatek" to "MediaTek",
        "mtk" to "MediaTek",
        "samsung" to "Samsung",
        "google" to "Google",
        "spreadtrum" to "Unisoc",
        "sprd" to "Unisoc",
        "unisoc" to "Unisoc",
        "hisilicon" to "HiSilicon",
        "rockchip" to "Rockchip",
        "nvidia" to "NVIDIA",
    )
    private const val OS_VERSION_MAX = 64
    private const val CPU_MAX = 96
    private const val SOC_MAX = 64
    private const val GPU_MAX = 96
    private const val SCREEN_MAX = 24
    private const val VENDOR_MAX = 32
    private const val MODEL_MAX = 64
    private const val ARCH_MAX = 16
    private const val LOCALE_MAX = 16
    private const val CPU_LIMIT = 512
}
