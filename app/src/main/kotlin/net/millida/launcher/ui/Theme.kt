package net.millida.launcher.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.Locales
import net.millida.launcher.core.i18n.Scripts
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trKey
import net.millida.launcher.core.looks.PremiumLook

@Immutable
data class MillidaColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val raised: Color,
    val inset: Color,
    val fg: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    val fgFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val success: Color,
    val dark: Boolean,
    val onAccent: Color,
    val accentText: Color,
    val accentToday: Color,
    val accentDeep: Color,
    val border: Color,
    val borderStrong: Color,
    val plateTop: Color,
    val plateBottom: Color,
    val wellOutline: Color,
    val wellTop: Color,
    val chipFill: Color,
    val segmentFill: Color,
    val segmentFillOn: Color,
    val skeletonShine: Color,
    val dockOutline: Color,
    val tickRead: Color,
)

private val InkOnAccent = Color(0xFF06200E)

private val TickRead = Color(0xFF4C9FFE)

private val darkTokens = MillidaColors(
    bg = Color(0xFF161718),
    surface = Color(0xFF1E1F20),
    surface2 = Color(0xFF26272A),
    raised = Color(0xFF2A2B2E),
    inset = Color(0xFF131314),
    fg = Color(0xFFEDEFEE),
    fgMuted = Color(0xCCEDEFEE),
    fgSubtle = Color(0xA8EDEFEE),
    fgFaint = Color(0x85EDEFEE),
    accent = Color(0xFF5EC64D),
    accentSoft = Color(0x215EC64D),
    danger = Color(0xFFFF6B5E),
    dangerSoft = Color(0x21FF6B5E),
    warning = Color(0xFFF5AA38),
    warningSoft = Color(0x24F5AA38),
    info = Color(0xFF6AA9E9),
    infoSoft = Color(0x246AA9E9),
    success = Color(0xFF5EC64D),
    dark = true,
    onAccent = InkOnAccent,
    accentText = Color(0xFF5EC64D),
    accentToday = Color(0xFF334D32),
    accentDeep = Color(0xFF11240E),
    border = Color(0x12FFFFFF),
    borderStrong = Color(0x24FFFFFF),
    plateTop = Color(0x0AFFFFFF),
    plateBottom = Color(0x42000000),
    wellOutline = Color(0x17EDEFEE),
    wellTop = Color(0x42000000),
    chipFill = Color(0x0FEDEFEE),
    segmentFill = Color(0x0AEDEFEE),
    segmentFillOn = Color(0x1FEDEFEE),
    skeletonShine = Color(0x0DEDEFEE),
    dockOutline = Color(0x66000000),
    tickRead = TickRead,
)

private val lightTokens = MillidaColors(
    bg = Color(0xFFF2F3F1),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF5F6F4),
    raised = Color(0xFFFFFFFF),
    inset = Color(0xFFE9EBE8),
    fg = Color(0xFF181D1A),
    fgMuted = Color(0xCC181D1A),
    fgSubtle = Color(0xA8181D1A),
    fgFaint = Color(0x8A181D1A),
    accent = Color(0xFF3B9A34),
    accentSoft = Color(0x1C3B9A34),
    danger = Color(0xFFDD4A3E),
    dangerSoft = Color(0x24DD4A3E),
    warning = Color(0xFFB97511),
    warningSoft = Color(0x29B97511),
    info = Color(0xFF3572B8),
    infoSoft = Color(0x263572B8),
    success = Color(0xFF3B9A34),
    dark = false,
    onAccent = InkOnAccent,
    accentText = Color(0xFF346D2A),
    accentToday = Color(0xFFD6E9D3),
    accentDeep = Color(0xFF11240E),
    border = Color(0x14181D1A),
    borderStrong = Color(0x29181D1A),
    plateTop = Color(0x00000000),
    plateBottom = Color(0x1F0F1711),
    wellOutline = Color(0x4D0F1711),
    wellTop = Color(0x14000000),
    chipFill = Color(0x0F181D1A),
    segmentFill = Color(0x0A181D1A),
    segmentFillOn = Color(0x1F181D1A),
    skeletonShine = Color(0x0D181D1A),
    dockOutline = Color(0x1F0F1711),
    tickRead = TickRead,
)

@Immutable
data class MillidaAccent(
    val id: String,
    val label: String,
    val color: Color,
    val soft: Color,
)

val MillidaAccents: List<MillidaAccent> get() = listOf(
    MillidaAccent("green", tr("Зелёный"), Color(0xFF5EC64D), Color(0x215EC64D)),
    MillidaAccent("lime", tr("Лайм"), Color(0xFF9BD628), Color(0x249BD628)),
    MillidaAccent("teal", tr("Бирюзовый"), Color(0xFF22C7A9), Color(0x2422C7A9)),
    MillidaAccent("cyan", tr("Голубой"), Color(0xFF2CB6E8), Color(0x242CB6E8)),
    MillidaAccent("blue", tr("Синий"), Color(0xFF4C8DFF), Color(0x244C8DFF)),
    MillidaAccent("indigo", tr("Индиго"), Color(0xFF6C6BFF), Color(0x246C6BFF)),
    MillidaAccent("purple", tr("Фиолетовый"), Color(0xFF9B6BFF), Color(0x249B6BFF)),
    MillidaAccent("pink", tr("Розовый"), Color(0xFFFF6BAA), Color(0x24FF6BAA)),
    MillidaAccent("red", tr("Красный"), Color(0xFFFF6B5E), Color(0x24FF6B5E)),
    MillidaAccent("orange", tr("Оранжевый"), Color(0xFFF5923B), Color(0x24F5923B)),
    MillidaAccent("yellow", tr("Жёлтый"), Color(0xFFF5C93B), Color(0x24F5C93B)),
    MillidaAccent("emerald", tr("Изумрудный"), Color(0xFF2BB673), Color(0x242BB673)),
    MillidaAccent("mint", tr("Мятный"), Color(0xFF5FE0B0), Color(0x245FE0B0)),
    MillidaAccent("sky", tr("Небесный"), Color(0xFF6FC3FF), Color(0x246FC3FF)),
    MillidaAccent("azure", tr("Лазурный"), Color(0xFF3A6FE0), Color(0x243A6FE0)),
    MillidaAccent("lavender", tr("Лавандовый"), Color(0xFFB79CFF), Color(0x24B79CFF)),
    MillidaAccent("magenta", tr("Малиновый"), Color(0xFFE8457A), Color(0x24E8457A)),
    MillidaAccent("coral", tr("Коралловый"), Color(0xFFFF8A65), Color(0x24FF8A65)),
    MillidaAccent("amber", tr("Янтарный"), Color(0xFFFFB020), Color(0x24FFB020)),
    MillidaAccent("sand", tr("Песочный"), Color(0xFFD8B26E), Color(0x24D8B26E)),
    MillidaAccent("silver", tr("Серебряный"), Color(0xFFBFC7CF), Color(0x24BFC7CF)),
)

/**
 * The subscriber's extra swatches (premiumTheme.ts PREMIUM_ACCENTS): known
 * accents like any other, so a stored choice is never reset to green; whether
 * the current tier opens them is the picker's and the theme's business.
 */
val PremiumAccents: List<MillidaAccent> get() = listOf(
    MillidaAccent(PremiumLook.ACCENT_PLUS, tr("Золото PLUS"), Color(0xFFF5BD2C), Color(0x26F5BD2C)),
    MillidaAccent(PremiumLook.ACCENT_DIAMOND, tr("Алмаз Diamond"), Color(0xFF4FB8FF), Color(0x264FB8FF)),
)

private val CUSTOM_ACCENT = Regex("^#[0-9a-fA-F]{6}$")

private const val CUSTOM_ACCENT_SOFT_ALPHA = 0.14f

fun isCustomAccent(id: String): Boolean = CUSTOM_ACCENT.matches(id)

fun isKnownAccent(id: String): Boolean =
    isCustomAccent(id) || MillidaAccents.any { it.id == id } || PremiumAccents.any { it.id == id }

fun accentFromHex(hex: String): MillidaAccent {
    val color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
    return MillidaAccent(hex.lowercase(), tr("Свой цвет"), color, color.copy(alpha = CUSTOM_ACCENT_SOFT_ALPHA))
}

fun accentById(id: String): MillidaAccent =
    MillidaAccents.firstOrNull { it.id == id }
        ?: PremiumAccents.firstOrNull { it.id == id }
        ?: if (isCustomAccent(id)) accentFromHex(id) else MillidaAccents.first()

/** CSS `color-mix(in srgb, this share, other)`: a plain per-channel blend, not Compose's Oklab `lerp`. */
fun Color.mixSrgb(other: Color, share: Float): Color {
    val keep = share.coerceIn(0f, 1f)
    val rest = 1f - keep
    return Color(
        red = red * keep + other.red * rest,
        green = green * keep + other.green * rest,
        blue = blue * keep + other.blue * rest,
        alpha = alpha * keep + other.alpha * rest,
    )
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

private fun inkFor(accent: Color): Color =
    if (contrast(accent, InkOnAccent) >= contrast(accent, Color.White)) InkOnAccent else Color.White

internal fun knobOn(accent: Color): Color =
    if (accent.luminance() > KNOB_INK_LUMINANCE) InkOnAccent else MillidaPixel.white

private const val KNOB_INK_LUMINANCE = 0.5f

private fun MillidaColors.withAccent(accent: MillidaAccent): MillidaColors {
    val ink = inkFor(accent.color)
    return copy(
        accent = accent.color,
        accentSoft = accent.soft,
        onAccent = ink,
        accentText = if (dark) accent.color else accent.color.mixSrgb(Color.Black, 0.55f),
        success = accent.color,
        accentToday = accent.color.mixSrgb(surface2, 0.24f),
        accentDeep = accent.color.mixSrgb(Color.Black, 0.18f),
    )
}

// Theme-independent: bevels and shades sit on coloured fills, not on the page background.
object MillidaPixel {
    val buttonOutline = Color(0x29000000)
    val buttonTop = Color(0x4DFFFFFF)
    val buttonBottom = Color(0x52000000)
    val buttonLeft = Color(0x1AFFFFFF)
    val buttonRight = Color(0x24000000)
    val pressOutline = Color(0x33000000)
    val pressTop = Color(0x38000000)
    val pressBottom = Color(0x38000000)
    val pressShade = Color(0x0A000000)
    val dangerTop = Color(0x1AFFFFFF)
    val dangerBottom = Color(0x42000000)
    val runningBottom = Color(0x38000000)
    val toggleOnTop = Color(0x3DFFFFFF)
    val toggleOnBottom = Color(0x42000000)
    val chipOnTop = Color(0x1AFFFFFF)
    val chipOnBottom = Color(0x4D000000)
    val tabOnBottom = Color(0x40000000)
    val knobShade = Color(0x40000000)
    val divider = Color(0x73000000)
    val barShine = Color(0x33FFFFFF)
    val textShadow = Color(0x61000000)
    val sole = Color(0x73000000)
    val tileTop = Color(0x4DFFFFFF)
    val tileBottom = Color(0x4D000000)
    val white = Color(0xFFFFFFFF)
    val whiteSoft = Color(0xE6FFFFFF)
    val counter = Color(0xFFE8202A)
    val counterInk = Color(0xFFFFFFFF)
    val alertInk = Color(0xFF1A0D05)
    val alertShade = Color(0x2E000000)

    /** Dark plates under a server's stand-in block, picked by name (the desktop's SERVER_TINTS). */
    val serverTints = listOf(
        Color(0xFF2F5D3A),
        Color(0xFF3B4F7A),
        Color(0xFF6A3F7A),
        Color(0xFF7A4A2F),
        Color(0xFF2F6A6A),
        Color(0xFF7A3F4F),
        Color(0xFF5A5A2F),
        Color(0xFF3F3F6A),
    )
    val glintStrong = Color(0x42FFFFFF)
    val glintSoft = Color(0x24FFFFFF)
    val glintMythicSoft = Color(0x2EFFFFFF)
    val sparkArm = 0.85f
    val mythicGlint = 0.55f
    val modalScrim = Color(0xB3000000)
    val sheetScrim = Color(0x9E000000)
    val sceneScrim = Color(0xF0030705)
    val revealCenter = Color(0xFF050807)
    val revealEdge = Color(0xFF030504)
    val revealPlate = Color(0xFF15181A)
    val revealInk = Color(0xFF111111)
    val revealShade = Color(0x47000000)
    val badgeGreen = Color(0xFF55FF55)
    val badgeGreenShade = Color(0xFF153F15)
    val badgeGold = Color(0xFFFFAA00)
    val badgeGoldShade = Color(0xFF3F2A00)
    val nameTagBack = Color(0x5C000000)
    val nameTagShade = Color(0xFF3F3F3F)
    val nickGold = Color(0xFFFFD76A)
    val nickGoldSoft = Color(0xFFFFE08A)
    val plusGlow = Color(0x38FFBE3C)
    val heroTop = Color(0xFF1F1914)
    val heroMiddle = Color(0xFF0B0D14)
    val heroBottom = Color(0xFF06070A)
    val heroGlow = Color(0x3DF3B525)
    val heroGrid = Color(0x0DFFFFFF)
    val heroLight = Color(0x57FFC933)
    val heroInk = Color(0xFF111820)
    val sceneBack = Color(0xFF1A120E)
    val sceneShade = Color(0x8C000000)
    val sceneNote = Color(0x9E000000)
    val tabBack = Color(0x80000000)
    val tabRow = Color(0x1AFFFFFF)
    val tabYou = Color(0x38F2AD0D)
    val rubyIcon = Color(0xFFFF5A8A)
    val gold = Color(0xFFFFC933)
    val goldInk = Color(0xFF3A2400)
    val goldTop = Color(0xFFFFE79A)
    val goldBottom = Color(0xFFC98A00)
    val dotX = floatArrayOf(0.11f, 0.31f, 0.57f, 0.76f, 0.90f, 0.21f, 0.66f, 0.44f)
    val dotY = floatArrayOf(0.26f, 0.68f, 0.22f, 0.66f, 0.34f, 0.80f, 0.12f, 0.46f)
    val dotColors = listOf(
        Color(0x33FFFFFF),
        Color(0x24FFFFFF),
        Color(0x2EFFFFFF),
        Color(0x1FFFFFFF),
        Color(0x2EFFFFFF),
        Color(0x24000000),
        Color(0x1F000000),
        Color(0x1A000000),
    )
    val prism = listOf(
        Color(0xFFFF4D5E),
        Color(0xFFFF9A3C),
        Color(0xFFFFE14D),
        Color(0xFF5EE06A),
        Color(0xFF4DC4FF),
        Color(0xFFB877FF),
    )
    val shopPlate = Color(0xFFE01F49)
    val shopPressed = Color(0xFFE43A5F)
    val shopOutline = Color(0xFF440815)
    val shopBand = Color(0xFF760D24)
    val shopRays = Color(0x6BF26E8A)
    val dailyPlate = Color(0xFFE89A1C)
    val dailyPressed = Color(0xFFF2A930)
    val dailyOutline = Color(0xFF4A2A00)
    val dailyBand = Color(0xFF8A4A06)
    val dailyRays = Color(0x66FFE28C)
    val wardrobePlate = Color(0xFF2F8FD6)
    val wardrobePressed = Color(0xFF3DA0E8)
    val wardrobeOutline = Color(0xFF0A2A44)
    val wardrobeBand = Color(0xFF17507D)
    val wardrobeRays = Color(0x66AAE1FF)
    val serverDark = Color(0xFF0B1F0E)
    val serverBandDark = Color(0xFF06140A)
    val socialWave = Color(0xFF2D2F6B)
    val socialWaveFlash = Color(0xFF7B82FF)
    val hostingWave = Color(0xFF0F5A3A)
    val hostingWaveFlash = Color(0xFF35D49A)
    val settingsWave = Color(0xFF23272B)
    val settingsWaveFlash = Color(0xFF5D6670)
    val modeBuild = Color(0xFF1F4F99)
    val modeBuildArt = Color(0xFF143566)
    val modePremium = Color(0xFFA8700C)
    val modePremiumArt = Color(0xFF6E4705)
    val modeServer = Color(0xFF5B3AA8)
    val modeServerArt = Color(0xFF3A2270)
    val modeOutline = Color(0x59000000)
    val modeTop = Color(0x38FFFFFF)
    val modeBottom = Color(0x4D000000)
    val modeCaption = Color(0xB8FFFFFF)
    val modeMeta = Color(0xD1FFFFFF)
    val modeEmpty = Color(0xFFB8760D)
    val modeEmptyBlink = Color(0xFFCF8A15)
    val modeEmptyArt = Color(0xFF6B4304)
    val modeEmptyIcon = Color(0xFFFFD36B)
    val lobbyDock = Color(0x8C04140A)
    val lobbyDockOutline = Color(0x73000000)
    val lobbyRec = Color(0xC70A1E10)
    val lobbyRecPressed = Color(0xE00E2A16)
    val lobbyRecOutline = Color(0x80000000)
    val lobbyRecTop = Color(0x1FFFFFFF)
    val lobbyRecBottom = Color(0x59000000)
    val lobbyRecArt = Color(0xFF1B2A20)
    val lobbyRecLabel = Color(0xFFFFD23F)
    val lobbyRecGoBottom = Color(0x40000000)
    val stageInk = Color(0xFF000000)
    val stageFeetShade = Color(0xFF00280C)
    val bannerCo = Color(0x99FFFFFF)
    val bannerTitle = Color(0xEBFFFFFF)
    val bannerTitleShadow = Color(0x80000000)
    val bannerGoShade = Color(0x38000000)
    val bannerDot = Color(0x59FFFFFF)
    val bannerDiscordNight = Color(0xFF2A2F86)
    val bannerDiscordLava = Color(0xFF5865F2)
    val bannerDiscordEm = Color(0xFFB9C0FF)
    val bannerTelegramNight = Color(0xFF0B4A6E)
    val bannerTelegramLava = Color(0xFF2AABEE)
    val bannerTelegramEm = Color(0xFF9FDCFF)
    val bannerPackNight = Color(0xFF15111D)
    val bannerPackLava = Color(0xFFFFC531)
    val bannerPackInk = Color(0xFF1C1405)
    val bannerPackOnline = Color(0xD115111D)
    val bubbleFill = Color(0xFFF6F5EF)
    val bubbleInk = Color(0xFF17171B)
    val bubbleShade = Color(0x1F000000)
    val clockPlate = Color(0x8C000000)
    val railArrowAlpha = 0.82f
    val plusChip = Color(0xFF2A1F08)
    val plusChipPressed = Color(0xFF3A2B0B)
    val diamondChip = Color(0xFF0B2140)
    val diamondChipPressed = Color(0xFF10305A)
    val plusChipInk = Color(0xFFFFE08A)
    val diamondChipInk = Color(0xFFBFE8FF)
    val plusAdd = Color(0xFFF5BD2C)
    val plusAddGlow = Color(0xE6FFBE28)
    val plusAddGlowDim = Color(0x66FFBE28)
    val plusMarkSole = Color(0xFF1C1003)
    val plusMarkLine = Color(0xFF3A2306)
    val plusMarkBody = Color(0xFFF5BD2C)
    val plusMarkHi = Color(0xFFFFE58A)
    val plusMarkLo = Color(0xFFC4840F)
    val diamondMarkSole = Color(0xFF06142B)
    val diamondMarkLine = Color(0xFF0B2346)
    val diamondMarkBody = Color(0xFF4FB8FF)
    val diamondMarkHi = Color(0xFFD2F1FF)
    val diamondMarkLo = Color(0xFF2A73D0)
    val offMarkSole = Color(0xFF111111)
    val offMarkLine = Color(0xFF222222)
    val offMarkBody = Color(0xFF8B8F93)
    val offMarkHi = Color(0xFFB9BDC1)
    val offMarkLo = Color(0xFF63676B)
    val markShine = Color(0x8CFFFFFF)
    val trustPlate = Color(0xFFFFFFFF)
    val trustInk = Color(0xFF10161B)
    val trustHeadLine = Color(0xFFE3E8EC)
    val trustRowLine = Color(0xFFECEFF2)
    val trustGreen = Color(0xFF1F9D3A)
    val trustTagInk = Color(0xFF1F7A34)
    val trustTagPlate = Color(0xFFE6F6EA)
    val trustMuted = Color(0xFF7A858F)
    val brandMicrosoft = listOf(Color(0xFFF25022), Color(0xFF7FBA00), Color(0xFF00A4EF), Color(0xFFFFB900))
    val brandMojang = Color(0xFFF0313B)
    val brandGithub = Color(0xFF181717)
}

enum class Rarity(val color: Color, private val plateKey: String, private val titleKey: String) {
    COMMON(Color(0xFF98A0A4), trKey("Обычная"), trKey("Обычная")),
    UNCOMMON(Color(0xFF5AC445), trKey("Необычная"), trKey("Необычная")),
    RARE(Color(0xFF4994E9), trKey("Редкая"), trKey("Редкая")),
    EPIC(Color(0xFFB466DB), trKey("Эпическая"), trKey("Эпическая")),
    LEGENDARY(Color(0xFFF2BA36), trKey("Легенда"), trKey("Легендарная")),
    MYTHIC(Color(0xFFF14159), trKey("Миф"), trKey("Мифическая")),
    RELIC(Color(0xFFEDEDF3), trKey("Невозможная"), trKey("Невозможная"));

    val plate: String get() = tr(plateKey)
    val title: String get() = tr(titleKey)

    val sparkles: Boolean get() = this >= EPIC
    val animated: Boolean get() = this >= MYTHIC
    val solidPlate: Boolean get() = this == LEGENDARY || this == MYTHIC

    companion object {
        val relicBackground = Color(0xFF060608)
        val ink = Color(0xFF1B1300)

        fun of(value: String?): Rarity? {
            val key = value?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == key }
        }
    }
}

object MillidaCut {
    val badge: Dp = 4.dp
    val control: Dp = 5.dp
    val plate: Dp = 10.dp
}

object MillidaShape {
    val badge: Shape = PixelCutShape(MillidaCut.badge)
    val mini: Shape = PixelCutShape(MillidaCut.control)
    val control: Shape = PixelCutShape(MillidaCut.control)
    val card: Shape = PixelCutShape(MillidaCut.plate)
    val avatar: Shape = PixelCutShape(MillidaCut.badge)
    val bubble: Shape = PixelCutShape(MillidaCut.control)
}

object MillidaMotion {
    const val fast = 120
    const val base = 200
    const val slow = 300
    const val press = 120
    const val tilePress = 90
    const val screen = 180
    const val hop = 1400
    val pressOffset: Dp = 2.dp
    val tilePressOffset: Dp = 3.dp
    val plop = CubicBezierEasing(0.2f, 1.5f, 0.4f, 1f)

    fun steps(count: Int): Easing = StepsEasing(count)
}

private object BaseType {
    val brand = TextStyle(
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.02).em,
    )
    val screenTitle = TextStyle(
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.02).em,
    )
    val barTitle = TextStyle(
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.01).em,
    )
    val dialogTitle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.01).em,
    )
    val sectionTitle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    )
    val caption = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.08.em,
    )
    val button = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.5.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.005).em,
    )
    val rowTitle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    )
    val body = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    val small = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    )
    val tileTitle = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Black,
        fontSize = 21.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.015).em,
    )
    val tabLabel = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        lineHeight = 13.sp,
    )
    val pixel = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        lineHeight = 17.sp,
    )
    val pixelLarge = TextStyle(
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    )
    val pixelSmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        lineHeight = 13.sp,
    )
}

object MillidaType {
    val brand: TextStyle get() = scale().brand
    val screenTitle: TextStyle get() = scale().screenTitle
    val barTitle: TextStyle get() = scale().barTitle
    val dialogTitle: TextStyle get() = scale().dialogTitle
    val sectionTitle: TextStyle get() = scale().sectionTitle
    val caption: TextStyle get() = scale().caption
    val button: TextStyle get() = scale().button
    val rowTitle: TextStyle get() = scale().rowTitle
    val body: TextStyle get() = scale().body
    val small: TextStyle get() = scale().small
    val tileTitle: TextStyle get() = scale().tileTitle
    val tabLabel: TextStyle get() = scale().tabLabel
    val pixel: TextStyle get() = scale().pixel
    val pixelLarge: TextStyle get() = scale().pixelLarge
    val pixelSmall: TextStyle get() = scale().pixelSmall

    @Volatile
    private var cached: TypeScale? = null

    private fun scale(): TypeScale {
        val script = ScriptType.of(I18n.code)
        return cached?.takeIf { it.script == script } ?: TypeScale(script).also { cached = it }
    }
}

internal class TypeScale(val script: ScriptType) {
    val brand: TextStyle = script.apply(BaseType.brand)
    val screenTitle: TextStyle = script.apply(BaseType.screenTitle)
    val barTitle: TextStyle = script.apply(BaseType.barTitle)
    val dialogTitle: TextStyle = script.apply(BaseType.dialogTitle)
    val sectionTitle: TextStyle = script.apply(BaseType.sectionTitle)
    val caption: TextStyle = script.apply(BaseType.caption)
    val button: TextStyle = script.apply(BaseType.button)
    val rowTitle: TextStyle = script.apply(BaseType.rowTitle)
    val body: TextStyle = script.apply(BaseType.body)
    val small: TextStyle = script.apply(BaseType.small)
    val tileTitle: TextStyle = script.apply(BaseType.tileTitle)
    val tabLabel: TextStyle = script.apply(BaseType.tabLabel)
    val pixel: TextStyle = script.apply(BaseType.pixel)
    val pixelLarge: TextStyle = script.apply(BaseType.pixelLarge)
    val pixelSmall: TextStyle = script.apply(BaseType.pixelSmall)
}

/** The current language's locale on every style: Han glyphs differ between Chinese and Japanese, and Urdu takes Nastaliq. */
internal data class ScriptType(val tag: String, val lineScale: Float, val systemFace: Boolean) {
    fun apply(style: TextStyle): TextStyle = style.copy(
        fontFamily = if (systemFace) FontFamily.Default else style.fontFamily,
        lineHeight = if (lineScale != 1f && style.lineHeight.isSp) (style.lineHeight.value * lineScale).sp else style.lineHeight,
        localeList = LocaleList(tag),
    )

    companion object {
        fun of(code: String): ScriptType = ScriptType(Locales.info(code).intl, Scripts.lineScale(code), Scripts.systemFace(code))
    }
}

val LocalMillidaColors: ProvidableCompositionLocal<MillidaColors> =
    staticCompositionLocalOf { darkTokens }

val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

val millida: MillidaColors
    @Composable get() = LocalMillidaColors.current

private fun typography(tokens: MillidaColors, script: ScriptType): Typography = baseTypography(tokens).let { t ->
    Typography(
        displayLarge = script.apply(t.displayLarge),
        displayMedium = script.apply(t.displayMedium),
        displaySmall = script.apply(t.displaySmall),
        headlineLarge = script.apply(t.headlineLarge),
        headlineMedium = script.apply(t.headlineMedium),
        headlineSmall = script.apply(t.headlineSmall),
        titleLarge = script.apply(t.titleLarge),
        titleMedium = script.apply(t.titleMedium),
        titleSmall = script.apply(t.titleSmall),
        bodyLarge = script.apply(t.bodyLarge),
        bodyMedium = script.apply(t.bodyMedium),
        bodySmall = script.apply(t.bodySmall),
        labelLarge = script.apply(t.labelLarge),
        labelMedium = script.apply(t.labelMedium),
        labelSmall = script.apply(t.labelSmall),
    )
}

private fun baseTypography(tokens: MillidaColors): Typography {
    val base = Typography()
    fun TextStyle.ui(): TextStyle = copy(fontFamily = GeistFamily)
    fun TextStyle.display(weight: FontWeight): TextStyle = copy(fontFamily = UnboundedFamily, fontWeight = weight)
    return Typography(
        displayLarge = base.displayLarge.display(FontWeight.Black),
        displayMedium = base.displayMedium.display(FontWeight.Black),
        displaySmall = base.displaySmall.display(FontWeight.Black),
        headlineLarge = base.headlineLarge.display(FontWeight.Bold),
        headlineMedium = base.headlineMedium.display(FontWeight.Bold),
        headlineSmall = BaseType.screenTitle.copy(color = tokens.fg),
        titleLarge = base.titleLarge.ui().copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = TextStyle(
            fontFamily = GeistFamily,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            color = tokens.fg,
        ),
        titleSmall = TextStyle(
            fontFamily = GeistFamily,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.fg,
        ),
        bodyLarge = base.bodyLarge.ui(),
        bodyMedium = TextStyle(
            fontFamily = GeistFamily,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.fgMuted,
        ),
        bodySmall = TextStyle(
            fontFamily = GeistFamily,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.fgSubtle,
        ),
        labelLarge = base.labelLarge.ui().copy(fontWeight = FontWeight.Bold),
        labelMedium = TextStyle(
            fontFamily = GeistFamily,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.fg,
        ),
        labelSmall = TextStyle(
            fontFamily = GeistFamily,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.fgSubtle,
        ),
    )
}

fun millidaTokens(dark: Boolean, accentId: String, premium: Boolean = false): MillidaColors {
    val tokens = (if (dark) darkTokens else lightTokens).withAccent(accentById(accentId))
    return if (premium) tokens.withPremiumLines() else tokens
}

/** premium-theme.css: thin lines in the theme's colour on docks and frames, nothing else repainted. */
private fun MillidaColors.withPremiumLines(): MillidaColors = copy(
    border = accent.copy(alpha = PREMIUM_LINE_ALPHA),
    dockOutline = accent.copy(alpha = PREMIUM_DOCK_ALPHA),
)

private const val PREMIUM_LINE_ALPHA = 0.28f
private const val PREMIUM_DOCK_ALPHA = 0.35f

@Composable
fun MillidaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accentId: String = "green",
    premium: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = remember(dark, accentId, premium) { millidaTokens(dark, accentId, premium) }
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    val scheme = if (dark) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.onAccent,
            secondary = tokens.info,
            background = tokens.bg,
            onBackground = tokens.fg,
            surface = tokens.surface,
            onSurface = tokens.fg,
            surfaceVariant = tokens.surface2,
            onSurfaceVariant = tokens.fgSubtle,
            error = tokens.danger,
            outline = tokens.fgFaint,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.onAccent,
            secondary = tokens.info,
            background = tokens.bg,
            onBackground = tokens.fg,
            surface = tokens.surface,
            onSurface = tokens.fg,
            surfaceVariant = tokens.surface2,
            onSurfaceVariant = tokens.fgSubtle,
            error = tokens.danger,
            outline = tokens.fgFaint,
        )
    }

    val language by I18n.locale.collectAsState()
    val script = remember(language) { ScriptType.of(language) }
    CompositionLocalProvider(
        LocalMillidaColors provides tokens,
        LocalReducedMotion provides reducedMotion,
        LocalLayoutDirection provides if (Locales.info(language).rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(colorScheme = scheme, typography = remember(tokens, script) { typography(tokens, script) }) {
            CompositionLocalProvider(LocalContentColor provides tokens.fg, content = content)
        }
    }
}
