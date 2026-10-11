package net.millida.launcher.ui.shop

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.Transformation
import kotlinx.coroutines.delay
import net.millida.launcher.RevealCard
import net.millida.launcher.RewardKind
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.RubyReward
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.pixelSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val CHEST_COLUMNS = 16
internal const val CHEST_ROWS = 18

/** Chest paint is an in-world colour, like a ruby: it ignores the launcher theme. */
internal class ChestPalette(
    val out: Int,
    val dark: Int,
    val main: Int,
    val light: Int,
    val hi: Int,
    val trimDark: Int,
    val trim: Int,
    val trimLight: Int,
    val inside: Int,
    val glow: Int,
    val spark: Int,
) {
    val glowColor: Color get() = Color(glow)
    val sparkColor: Color get() = Color(spark)
}

private val GOLD_TRIM = intArrayOf(0xFF9A6A08.toInt(), 0xFFE8B923.toInt(), 0xFFFFE685.toInt())

private val COMMON_PAINT = ChestPalette(
    out = 0xFF2E1A08.toInt(),
    dark = 0xFF6A4116.toInt(),
    main = 0xFF9C6428.toInt(),
    light = 0xFFB97C38.toInt(),
    hi = 0xFFD69A52.toInt(),
    trimDark = 0xFF3C4148.toInt(),
    trim = 0xFF8D949E.toInt(),
    trimLight = 0xFFD3D8DE.toInt(),
    inside = 0xFF1C1006.toInt(),
    glow = 0xFFFFD56A.toInt(),
    spark = 0xFFFFF1B8.toInt(),
)

private val RARE_PAINT = ChestPalette(
    out = 0xFF0B1A44.toInt(),
    dark = 0xFF1B3B94.toInt(),
    main = 0xFF2C5BD1.toInt(),
    light = 0xFF437CF0.toInt(),
    hi = 0xFF79A6FF.toInt(),
    trimDark = GOLD_TRIM[0],
    trim = GOLD_TRIM[1],
    trimLight = GOLD_TRIM[2],
    inside = 0xFF08112E.toInt(),
    glow = 0xFF58B0FF.toInt(),
    spark = 0xFFFFE685.toInt(),
)

private val EPIC_PAINT = ChestPalette(
    out = 0xFF210C42.toInt(),
    dark = 0xFF4A2090.toInt(),
    main = 0xFF7338D0.toInt(),
    light = 0xFF9158EC.toInt(),
    hi = 0xFFBB8CFF.toInt(),
    trimDark = GOLD_TRIM[0],
    trim = GOLD_TRIM[1],
    trimLight = GOLD_TRIM[2],
    inside = 0xFF14062A.toInt(),
    glow = 0xFFC07BFF.toInt(),
    spark = 0xFFFFE685.toInt(),
)

private val LEGEND_PAINT = ChestPalette(
    out = 0xFF351C02.toInt(),
    dark = 0xFFA4680A.toInt(),
    main = 0xFFE0A51D.toInt(),
    light = 0xFFF5C83B.toInt(),
    hi = 0xFFFFF09C.toInt(),
    trimDark = 0xFF560D17.toInt(),
    trim = 0xFFB01F2D.toInt(),
    trimLight = 0xFFFF6B6B.toInt(),
    inside = 0xFF2A1502.toInt(),
    glow = 0xFFFFD84A.toInt(),
    spark = 0xFFFFFBE0.toInt(),
)

internal fun chestPalette(tier: String): ChestPalette = when (tier.uppercase()) {
    "RARE" -> RARE_PAINT
    "EPIC" -> EPIC_PAINT
    "LEGEND", "LEGENDARY" -> LEGEND_PAINT
    else -> COMMON_PAINT
}

internal fun chestName(tier: String): String = when (tier.uppercase()) {
    "RARE" -> "Редкий"
    "EPIC" -> "Эпический"
    "LEGEND", "LEGENDARY" -> "Легендарный"
    else -> "Обычный"
}

internal fun chestRarity(tier: String): Rarity = when (tier.uppercase()) {
    "RARE" -> Rarity.RARE
    "EPIC" -> Rarity.EPIC
    "LEGEND", "LEGENDARY" -> Rarity.LEGENDARY
    else -> Rarity.COMMON
}

internal fun chestTone(tier: String): Color =
    if (chestRarity(tier) == Rarity.COMMON) chestPalette(tier).glowColor else chestRarity(tier).color

private enum class Face { BODY_FRONT, LID_FRONT, LID_TOP, INSIDE, LATCH }

/** Faces of the voxel chest, painted with the same texels as the flat one. */
internal enum class ChestFace { BODY, LID_SIDE, LID_TOP, INSIDE, INSIDE_LIT, LATCH }

private fun texelNoise(x: Int, y: Int, seed: Int): Double {
    var h = x * 374761393 + y * 668265263 + seed * 2147483647
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967295.0
}

private class TexelGrid(val columns: Int, val rows: Int) {
    val cells = IntArray(columns * rows)

    fun put(x: Int, y: Int, color: Int) {
        if (x in 0 until columns && y in 0 until rows) cells[y * columns + x] = color
    }

    fun fill(x: Int, y: Int, width: Int, color: Int) {
        for (dx in 0 until width) put(x + dx, y, color)
    }
}

private fun TexelGrid.paint(face: Face, p: ChestPalette, gold: Boolean, w: Int, h: Int, ox: Int, oy: Int, seed: Int) {
    if (face == Face.INSIDE) {
        for (y in 0 until h) for (x in 0 until w) {
            put(ox + x, oy + y, if (texelNoise(x, y, seed) > 0.8) p.out else p.inside)
        }
        return
    }
    if (face == Face.LATCH) {
        for (y in 0 until h) for (x in 0 until w) {
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
            put(ox + x, oy + y, if (edge) p.trimDark else if (y == 1) p.trimLight else p.trim)
        }
        if (w >= 2 && h >= 3) put(ox + w / 2, oy + h - 2, p.out)
        return
    }
    val lid = face == Face.LID_FRONT || face == Face.LID_TOP
    for (y in 0 until h) {
        for (x in 0 until w) {
            val n = texelNoise(x, y, seed)
            var c = when {
                n < 0.18 -> p.dark
                n < 0.72 -> p.main
                n < 0.94 -> p.light
                else -> p.hi
            }
            if (face != Face.LID_TOP && (y + if (lid) 1 else 0) % 4 == 3) c = if (n < 0.5) p.dark else p.out
            if (face == Face.LID_TOP && x % 5 == 4) c = if (n < 0.5) p.dark else p.main
            if (lid && face != Face.LID_TOP && y == 1) c = if (n < 0.6) p.light else p.hi
            put(ox + x, oy + y, c)
        }
    }
    if (gold) {
        for (x in 1 until w - 1) {
            put(ox + x, oy + 1, if (x % 3 == 0) p.trimLight else p.trim)
            put(ox + x, oy + h - 2, p.trimDark)
        }
        for (y in 1 until h - 1) {
            put(ox + 1, oy + y, if (y == 1) p.trimLight else p.trim)
            put(ox + w - 2, oy + y, p.trimDark)
        }
        if (face == Face.LID_TOP) {
            val cx = w / 2 - 1
            val cy = h / 2 - 1
            put(ox + cx, oy + cy, p.trimLight)
            put(ox + cx + 1, oy + cy, p.trim)
            put(ox + cx, oy + cy + 1, p.trim)
            put(ox + cx + 1, oy + cy + 1, p.trimDark)
            for ((dx, dy) in listOf(-1 to 0, 2 to 1, 0 to -1, 1 to 2)) put(ox + cx + dx, oy + cy + dy, p.hi)
        }
    } else {
        for ((cx, cy) in listOf(1 to 1, w - 3 to 1, 1 to h - 3, w - 3 to h - 3)) {
            if (cy < 0 || cy + 1 >= h) continue
            put(ox + cx, oy + cy, p.trimLight)
            put(ox + cx + 1, oy + cy, p.trim)
            put(ox + cx, oy + cy + 1, p.trim)
            put(ox + cx + 1, oy + cy + 1, p.trimDark)
        }
    }
    for (x in 0 until w) {
        put(ox + x, oy, p.out)
        put(ox + x, oy + h - 1, p.out)
    }
    for (y in 0 until h) {
        put(ox, oy + y, p.out)
        put(ox + w - 1, oy + y, p.out)
    }
}

internal object ChestPaint {
    private val cache = HashMap<String, IntArray>()
    private val faces = HashMap<String, IntArray>()

    /** ARGB texels of one face, row by row; cached because every frame of the 3D chest reuses them. */
    fun face(tier: String, face: ChestFace, width: Int, height: Int, seed: Int): IntArray = synchronized(faces) {
        val key = "${tier.uppercase()}:$face:$width:$height:$seed"
        faces.getOrPut(key) {
            val p = chestPalette(tier)
            val gold = chestRarity(tier) != Rarity.COMMON
            val grid = TexelGrid(width, height)
            when (face) {
                ChestFace.BODY -> grid.paint(Face.BODY_FRONT, p, gold, width, height, 0, 0, seed)
                ChestFace.LID_SIDE -> grid.paint(Face.LID_FRONT, p, gold, width, height, 0, 0, seed)
                ChestFace.LID_TOP -> grid.paint(Face.LID_TOP, p, gold, width, height, 0, 0, seed)
                ChestFace.INSIDE -> grid.paint(Face.INSIDE, p, gold, width, height, 0, 0, seed)
                ChestFace.INSIDE_LIT -> {
                    grid.paint(Face.INSIDE, p, gold, width, height, 0, 0, seed)
                    for (y in 2 until height - 2) grid.fill(2, y, width - 4, p.glow)
                    for (y in 4 until height - 4) grid.fill(4, y, width - 8, p.spark)
                }
                ChestFace.LATCH -> grid.paint(Face.LATCH, p, gold, width, height, 0, 0, seed)
            }
            grid.cells
        }
    }

    fun grid(tier: String, open: Boolean): IntArray = synchronized(cache) {
        val key = tier.uppercase() + if (open) ":open" else ":closed"
        cache.getOrPut(key) { build(tier, open) }
    }

    private fun build(tier: String, open: Boolean): IntArray {
        val p = chestPalette(tier)
        val gold = chestRarity(tier) != Rarity.COMMON
        val grid = TexelGrid(CHEST_COLUMNS, CHEST_ROWS)
        if (open) {
            grid.paint(Face.LID_FRONT, p, gold, CHEST_COLUMNS, 5, 0, 0, 7)
            grid.paint(Face.INSIDE, p, gold, CHEST_COLUMNS, 3, 0, 5, 3)
            grid.fill(3, 6, 10, p.glow)
            grid.fill(5, 6, 6, p.spark)
        } else {
            grid.paint(Face.LID_TOP, p, gold, CHEST_COLUMNS, 3, 0, 0, 5)
            grid.paint(Face.LID_FRONT, p, gold, CHEST_COLUMNS, 5, 0, 3, 7)
        }
        grid.paint(Face.BODY_FRONT, p, gold, CHEST_COLUMNS, 10, 0, 8, 11)
        if (!open) grid.paint(Face.LATCH, p, gold, 2, 4, 7, 6, 1)
        return grid.cells
    }
}

internal fun DrawScope.drawTexels(
    grid: IntArray,
    left: Float,
    top: Float,
    cell: Float,
    from: Int = 0,
    to: Int = CHEST_ROWS,
    rowHeight: Float = cell,
    alpha: Float = 1f,
    skip: (Int, Int) -> Boolean = { _, _ -> false },
) {
    for (row in from until to) {
        val y = floor(top + (row - from) * rowHeight)
        val bottom = floor(top + (row - from + 1) * rowHeight)
        val height = bottom - y
        if (height <= 0f) continue
        var x = 0
        while (x < CHEST_COLUMNS) {
            val color = grid[row * CHEST_COLUMNS + x]
            if (color == 0 || skip(x, row)) {
                x++
                continue
            }
            var end = x + 1
            while (end < CHEST_COLUMNS && grid[row * CHEST_COLUMNS + end] == color && !skip(end, row)) end++
            drawRect(
                color = Color(color).copy(alpha = alpha),
                topLeft = Offset(left + x * cell, y),
                size = Size((end - x) * cell, height),
            )
            x = end
        }
    }
}

@Composable
internal fun ChestArt(
    tier: String,
    modifier: Modifier = Modifier,
    side: Dp = 64.dp,
    alpha: Float = 1f,
) {
    val grid = remember(tier) { ChestPaint.grid(tier, open = false) }
    Canvas(modifier.size(side)) {
        val cell = max(1f, floor(min(size.width / CHEST_COLUMNS, size.height / CHEST_ROWS)))
        val left = floor((size.width - cell * CHEST_COLUMNS) / 2f)
        val top = floor((size.height - cell * CHEST_ROWS) / 2f)
        drawTexels(grid, left, top, cell, alpha = alpha)
    }
}

internal fun slotIcon(slot: String): MillidaIcon = when (slot.uppercase()) {
    "HAT", "HEAD", "HELMET" -> MIcons.hat
    "WINGS" -> MIcons.wings
    "CAPE", "CLOAK" -> MIcons.cape
    "BACK", "BACKPACK" -> MIcons.backpack
    "EARS" -> MIcons.ears
    "GLASSES", "FACE", "MASK" -> MIcons.glasses
    "PET" -> MIcons.paw
    "EMOTE" -> MIcons.smile
    "AURA", "EFFECT", "PARTICLE" -> MIcons.sparkle
    "BODY", "SHIRT" -> MIcons.shirt
    "SHOES", "FEET" -> MIcons.shoes
    "HAND" -> MIcons.hand
    "PANTS", "LEGS" -> MIcons.pants
    else -> MIcons.gift
}

/** The CDN keeps one base image per item; a variant is repainted by hue on the client. */
@Composable
internal fun ItemArt(item: RubyItem, modifier: Modifier = Modifier, side: Dp = 72.dp) {
    val context = LocalContext.current
    val url = item.image
    val tint = item.tintFrom.takeIf { from ->
        from.isNotBlank() && item.color.isNotBlank() && !from.trimStart('#').equals(item.color.trimStart('#'), ignoreCase = true)
    }
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.size(side), contentAlignment = Alignment.Center) {
        if (url == null || failed) {
            MIcon(slotIcon(item.slot), size = side * 0.5f)
        } else {
            val request = remember(url, tint, item.color) {
                val builder = ImageRequest.Builder(context).data(url)
                if (tint != null) builder.allowHardware(false).transformations(VariantTint(tint, item.color))
                builder.build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                onError = { failed = true },
            )
        }
    }
}

private class VariantTint(private val from: String, private val to: String) : Transformation {
    override val cacheKey: String = "variant-tint:$from:$to"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val target = parseRgb(to) ?: return input
        val targetHsl = rgbToHsl(target[0], target[1], target[2])
        val base = parseRgb(from)?.let { rgbToHsl(it[0], it[1], it[2]) }
        val lightness = if (base != null) (targetHsl[2] + 0.05f) / (base[2] + 0.05f) else 1f
        val out = input.copy(Bitmap.Config.ARGB_8888, true) ?: return input
        val width = out.width
        val height = out.height
        val pixels = IntArray(width * height)
        out.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = pixel ushr 24
            if (alpha < 16) continue
            val hsl = rgbToHsl((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
            if (hsl[1] < 0.18f) continue
            if (base != null && base[1] > 0.15f && hueGap(hsl[0], base[0]) > 38f) continue
            val saturation = if (targetHsl[1] < 0.08f) {
                targetHsl[1]
            } else {
                min(1f, hsl[1] * (targetHsl[1] / max(0.2f, base?.get(1) ?: hsl[1])))
            }
            val light = (hsl[2] * lightness).coerceIn(0.03f, 0.97f)
            val rgb = hslToRgb(targetHsl[0], saturation, light)
            pixels[index] = (alpha shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}

private fun parseRgb(value: String): IntArray? {
    val hex = value.trim().trimStart('#')
    if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return intArrayOf(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
}

private fun rgbToHsl(red: Int, green: Int, blue: Int): FloatArray {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val l = (high + low) / 2f
    if (high == low) return floatArrayOf(0f, 0f, l)
    val d = high - low
    val s = if (l > 0.5f) d / (2f - high - low) else d / (high + low)
    val h = when (high) {
        r -> (g - b) / d + if (g < b) 6f else 0f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return floatArrayOf(h * 60f, s, l)
}

private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
    val a = s * min(l, 1f - l)
    fun channel(n: Int): Int {
        val k = (n + h / 30f) % 12f
        val value = l - a * max(-1f, min(k - 3f, min(9f - k, 1f)))
        return (value * 255f).roundToInt().coerceIn(0, 255)
    }
    return intArrayOf(channel(0), channel(8), channel(4))
}

private fun hueGap(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

@Composable
internal fun PlusBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .pixelSurface(
                fill = MillidaPixel.gold,
                cut = MillidaCut.badge,
                frame = PixelFrame(top = MillidaPixel.goldTop, topWidth = 2.dp, bottom = MillidaPixel.goldBottom, bottomWidth = 3.dp),
            )
            .padding(start = 7.dp, end = 8.dp, top = 3.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.crown, size = 14.dp, color = MillidaPixel.goldInk)
        Spacer(Modifier.width(4.dp))
        Text(
            "PLUS",
            style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 0.6.sp),
            color = MillidaPixel.goldInk,
            maxLines = 1,
        )
    }
}

@Composable
internal fun RewardGlyph(
    kind: String,
    modifier: Modifier = Modifier,
    side: Dp = 40.dp,
    tier: String = "",
    item: RubyItem? = null,
    alpha: Float = 1f,
) {
    Box(modifier.size(side), contentAlignment = Alignment.Center) {
        when (kind) {
            RewardKind.RUBIES -> MIcon(MIcons.ruby, size = side * 0.86f, modifier = Modifier.alphaOf(alpha))
            RewardKind.SHARDS -> MIcon(MIcons.shard, size = side * 0.86f, modifier = Modifier.alphaOf(alpha))
            RewardKind.CHEST -> ChestArt(tier, side = side, alpha = alpha)
            RewardKind.PLUS -> MIcon(MIcons.crown, size = side * 0.8f, color = Rarity.LEGENDARY.color, modifier = Modifier.alphaOf(alpha))
            RewardKind.STARS -> MIcon(MIcons.star, size = side * 0.8f, color = Rarity.LEGENDARY.color, modifier = Modifier.alphaOf(alpha))
            RewardKind.CREATOR -> MAvatar(url = item?.image, size = side, icon = MIcons.heart)
            RewardKind.FRAGMENTS, RewardKind.ITEM -> if (item != null) {
                ItemArt(item, side = side, modifier = Modifier.alphaOf(alpha))
            } else {
                MIcon(if (kind == RewardKind.FRAGMENTS) MIcons.shard else MIcons.gift, size = side * 0.8f, modifier = Modifier.alphaOf(alpha))
            }
            else -> MIcon(MIcons.gift, size = side * 0.8f, modifier = Modifier.alphaOf(alpha))
        }
    }
}

private fun Modifier.alphaOf(value: Float): Modifier = if (value >= 1f) this else alpha(value)

internal fun rewardRarity(kind: String, tier: String, rarity: String, item: RubyItem?): Rarity? = when (kind) {
    RewardKind.CHEST -> chestRarity(tier)
    RewardKind.ITEM, RewardKind.FRAGMENTS -> Rarity.of(rarity) ?: Rarity.of(item?.rarity) ?: Rarity.EPIC
    else -> null
}

internal fun rewardTone(kind: String, tier: String, rarity: String, item: RubyItem?): Color = when (kind) {
    RewardKind.RUBIES -> MillidaPixel.rubyIcon
    RewardKind.SHARDS -> Rarity.RARE.color
    RewardKind.CHEST -> chestTone(tier)
    RewardKind.PLUS, RewardKind.STARS -> Rarity.LEGENDARY.color
    RewardKind.CREATOR -> MillidaPixel.counter
    else -> rewardRarity(kind, tier, rarity, item)?.color ?: Rarity.EPIC.color
}

internal fun RevealCard.tone(): Color = rewardTone(kind, tier, rarity, item)

internal fun rewardLabel(kind: String, amount: Long, tier: String, rarity: String, item: RubyItem?): String = when (kind) {
    RewardKind.RUBIES, RewardKind.SHARDS -> groupDigits(amount)
    RewardKind.CHEST -> chestName(tier)
    RewardKind.FRAGMENTS -> "Фрагменты"
    else -> item?.name?.takeIf { it.isNotBlank() } ?: (rewardRarity(kind, tier, rarity, item)?.title ?: "Вещь")
}

internal fun RubyReward.label(): String = rewardLabel(kind, amount, tier, rarity, item)

internal fun RubyReward.spoken(): String = when (kind) {
    RewardKind.RUBIES -> "$amount ${rubyWord(amount)}"
    RewardKind.SHARDS -> "$amount ${shardWord(amount)}"
    RewardKind.CHEST -> chestName(tier) + " сундук"
    RewardKind.FRAGMENTS -> "$amount ${fragmentWord(amount)}" + (item?.name?.let { " «$it»" } ?: "")
    else -> (item?.name ?: "Вещь") + ", навсегда"
}

internal fun rarityIndex(rarity: Rarity?): Int = rarity?.ordinal ?: 1

private fun plural(count: Long, one: String, few: String, many: String): String {
    val tens = abs(count) % 100
    val ones = tens % 10
    return when {
        tens in 11..19 -> many
        ones == 1L -> one
        ones in 2..4 -> few
        else -> many
    }
}

internal fun rubyWord(count: Long): String = plural(count, "рубин", "рубина", "рубинов")

internal fun shardWord(count: Long): String = plural(count, "осколок", "осколка", "осколков")

internal fun fragmentWord(count: Long): String = plural(count, "фрагмент", "фрагмента", "фрагментов")

internal fun chestWord(count: Long): String = plural(count, "сундук", "сундука", "сундуков")

internal fun rubles(kopecks: Long): String {
    val whole = kopecks / 100
    val rest = abs(kopecks % 100)
    return if (rest == 0L) "${groupDigits(whole)} ₽" else "${groupDigits(whole)},${rest.toString().padStart(2, '0')} ₽"
}

internal fun kopecksForRubies(rubies: Long): Long = (rubies * 100 + 6) / 7

private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

internal fun parseInstant(iso: String?): Instant? {
    val text = iso?.trim().orEmpty()
    if (text.isEmpty()) return null
    return try {
        Instant.parse(text)
    } catch (malformed: DateTimeParseException) {
        null
    }
}

/** The shop day changes at 00:00 MSK. */
internal fun dayMonth(iso: String?): String? = parseInstant(iso)?.let { DAY_MONTH.format(it.atZone(MOSCOW)) }

internal fun leftLabel(ms: Long): String {
    if (ms <= 60_000L) return "меньше минуты"
    val minutes = ms / 60_000L
    val days = minutes / 1440
    val hours = (minutes % 1440) / 60
    val rest = minutes % 60
    return when {
        days > 0 -> "${days}д ${hours}ч"
        hours > 0 -> "${hours}ч ${rest.toString().padStart(2, '0')}м"
        else -> "$rest мин"
    }
}

internal fun timerText(ms: Long): String {
    val seconds = max(0L, (ms + 999) / 1000)
    val minutes = (seconds % 3600) / 60
    return "${seconds / 3600}:${minutes.toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun rememberNow(periodMs: Long): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

private const val DUE_RETRY_MS = 3_000L
private const val DUE_RETRIES = 6

/** The server picks the day by its own clock: a phone running ahead gets the old plan back, so the refresh repeats. */
internal suspend fun refreshWhenDue(at: Long, onElapsed: () -> Unit) {
    delay((at - System.currentTimeMillis()).coerceAtLeast(0L))
    var wait = DUE_RETRY_MS
    repeat(DUE_RETRIES) {
        onElapsed()
        delay(wait)
        wait *= 2
    }
}

internal val CountEasing = Easing { fraction ->
    val rest = 1f - fraction
    1f - rest * rest * rest
}

@Composable
internal fun rememberCounter(target: Long, start: Long = target): State<Long> {
    val reduced = LocalReducedMotion.current
    val value = remember { Animatable(start.toFloat()) }
    val shown = remember { mutableLongStateOf(start) }
    LaunchedEffect(target) {
        if (reduced) {
            value.snapTo(target.toFloat())
            shown.longValue = target
        } else {
            value.animateTo(target.toFloat(), tween(COUNT_MS, easing = CountEasing)) {
                shown.longValue = this.value.roundToLong()
            }
            shown.longValue = target
        }
    }
    return shown
}

internal const val COUNT_MS = 700
