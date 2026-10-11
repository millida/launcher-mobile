package net.millida.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PX_EMPTY = '.'
private const val PX_TINT_KEY = 'c'
private const val PX_TINT_SOFT_KEY = 'C'
private const val PX_TINT = -1
private const val PX_TINT_SOFT = -2
private const val PX_SOFT_ALPHA = 0.55f
private const val PX_SNAP_BIAS = 0.4f
private const val PX_RUN_FIELDS = 4

@Immutable
class MillidaIcon internal constructor(cells: String) {
    internal val side: Int = sqrt(cells.length.toDouble()).roundToInt()
    internal val runs: IntArray

    init {
        require(side > 0 && side * side == cells.length) {
            "A pixel glyph must be a square grid, got ${cells.length} cells"
        }
        val packed = ArrayList<Int>()
        for (y in 0 until side) {
            var x = 0
            while (x < side) {
                val key = cells[y * side + x]
                if (key == PX_EMPTY) {
                    x++
                    continue
                }
                var end = x + 1
                while (end < side && cells[y * side + end] == key) end++
                packed += x
                packed += y
                packed += end - x
                packed += pxColorCode(key)
                x = end
            }
        }
        runs = packed.toIntArray()
    }
}

private fun pxColorCode(key: Char): Int = when (key) {
    PX_TINT_KEY -> PX_TINT
    PX_TINT_SOFT_KEY -> PX_TINT_SOFT
    else -> PxArt.PALETTE_KEYS.indexOf(key).also { index ->
        require(index >= 0) { "Pixel glyph uses a colour key '$key' that is not in the palette" }
    }
}

object MIcons {
    val box = MillidaIcon(PxArt.box)
    val server = MillidaIcon(PxArt.server)
    val search = MillidaIcon(PxArt.search)
    val user = MillidaIcon(PxArt.user)
    val settings = MillidaIcon(PxArt.settings)
    val play = MillidaIcon(PxArt.play)
    val plus = MillidaIcon(PxArt.plus)
    val minus = MillidaIcon(PxArt.minus)
    val trash = MillidaIcon(PxArt.trash)
    val close = MillidaIcon(PxArt.x)
    val check = MillidaIcon(PxArt.check)
    val chevronDown = MillidaIcon(PxArt.chevD)
    val alert = MillidaIcon(PxArt.alert)
    val info = MillidaIcon(PxArt.info)
    val copy = MillidaIcon(PxArt.copy)
    val star = MillidaIcon(PxArt.star)
    val download = MillidaIcon(PxArt.download)
    val wallet = MillidaIcon(PxArt.wallet)
    val send = MillidaIcon(PxArt.send)
    val back = MillidaIcon(PxArt.chevL)
    val phone = MillidaIcon(PxArt.phone)
    val logout = MillidaIcon(PxArt.logout)
    val shield = MillidaIcon(PxArt.shield)
    val folder = MillidaIcon(PxArt.folder)
    val eye = MillidaIcon(PxArt.eye)
    val filter = MillidaIcon(PxArt.filter)
    val grid = MillidaIcon(PxArt.grid)

    val chevronLeft: MillidaIcon get() = back
    val chevronRight = MillidaIcon(PxArt.chevR)
    val chevronUp = MillidaIcon(PxArt.chevU)
    val arrowLeft = MillidaIcon(PxArt.arrowL)
    val arrowRight = MillidaIcon(PxArt.arrowR)
    val arrowUp = MillidaIcon(PxArt.arrowUp)
    val arrowDown = MillidaIcon(PxArt.arrowDn)
    val refresh = MillidaIcon(PxArt.restart)
    val upload = MillidaIcon(PxArt.upload)
    val externalLink = MillidaIcon(PxArt.ext)
    val share: MillidaIcon get() = externalLink
    val link = MillidaIcon(PxArt.link)
    val more = MillidaIcon(PxArt.dots)
    val reply = MillidaIcon(PxArt.reply)
    val pause = MillidaIcon(PxArt.pause)
    val list = MillidaIcon(PxArt.list)
    val grip = MillidaIcon(PxArt.grip)
    val power = MillidaIcon(PxArt.power)
    val login = MillidaIcon(PxArt.login)
    val edit = MillidaIcon(PxArt.edit)
    val brush = MillidaIcon(PxArt.brush)
    val thumbUp = MillidaIcon(PxArt.thumb)

    val menuGear = MillidaIcon(PxArt.moGear)
    val menuPencil = MillidaIcon(PxArt.moPencil)
    val menuFolder = MillidaIcon(PxArt.moFolder)
    val menuTrash = MillidaIcon(PxArt.moTrash)
    val menuImage = MillidaIcon(PxArt.moImage)
    val menuKey = MillidaIcon(PxArt.moKey)
    val menuShield = MillidaIcon(PxArt.moShield)
    val menuUser = MillidaIcon(PxArt.moUser)
    val menuBag = MillidaIcon(PxArt.moBag)

    val chat = MillidaIcon(PxArt.msg)
    val phoneHang = MillidaIcon(PxArt.phoneHang)
    val phoneOff = MillidaIcon(PxArt.phoneOff)
    val mic = MillidaIcon(PxArt.mic)
    val micOff = MillidaIcon(PxArt.micOff)
    val headset = MillidaIcon(PxArt.headset)
    val headsetOff = MillidaIcon(PxArt.headsetOff)
    val volume = MillidaIcon(PxArt.volume)
    val mute = MillidaIcon(PxArt.mute)
    val music = MillidaIcon(PxArt.music)
    val musicOff = MillidaIcon(PxArt.musicOff)
    val camera = MillidaIcon(PxArt.cam)
    val screenShare = MillidaIcon(PxArt.screenShare)
    val smile = MillidaIcon(PxArt.smile)

    val users = MillidaIcon(PxArt.users)
    val chest = MillidaIcon(PxArt.chest)
    val gift = MillidaIcon(PxArt.gift)
    val crown = MillidaIcon(PxArt.crown)
    val gem = MillidaIcon(PxArt.gem)
    val shard = MillidaIcon(PxArt.shard)
    val emerald = MillidaIcon(PxArt.emerald)
    val trophy = MillidaIcon(PxArt.trophy)
    val heart = MillidaIcon(PxArt.heart)
    val heartOff = MillidaIcon(PxArt.heartOff)
    val sparkle = MillidaIcon(PxArt.sparkle)
    val zap = MillidaIcon(PxArt.zap)
    val flame = MillidaIcon(PxArt.flame)
    val sun = MillidaIcon(PxArt.sun)
    val moon = MillidaIcon(PxArt.moon)
    val bell = MillidaIcon(PxArt.bell)
    val lock = MillidaIcon(PxArt.lock)
    val key = MillidaIcon(PxArt.key)
    val clock = MillidaIcon(PxArt.clock)
    val map = MillidaIcon(PxArt.map)
    val book = MillidaIcon(PxArt.book)
    val image = MillidaIcon(PxArt.image)
    val monitor = MillidaIcon(PxArt.monitor)
    val inbox = MillidaIcon(PxArt.inbox)
    val flag = MillidaIcon(PxArt.flag)
    val blocks = MillidaIcon(PxArt.blocks)
    val hammer = MillidaIcon(PxArt.hammer)
    val ban = MillidaIcon(PxArt.ban)

    val shirt = MillidaIcon(PxArt.shirt)
    val looks = MillidaIcon(PxArt.looks)
    val hat = MillidaIcon(PxArt.hat)
    val glasses = MillidaIcon(PxArt.glasses)
    val ears = MillidaIcon(PxArt.ear)
    val backpack = MillidaIcon(PxArt.backpack)
    val wings = MillidaIcon(PxArt.wings)
    val cape = MillidaIcon(PxArt.cape)
    val hand = MillidaIcon(PxArt.hand)
    val shoes = MillidaIcon(PxArt.shoes)
    val body = MillidaIcon(PxArt.body)
    val pants = MillidaIcon(PxArt.pants)
    val paw = MillidaIcon(PxArt.paw)

    val wardrobeLooks = MillidaIcon(PxArt.wsLooks)
    val wardrobeSkin = MillidaIcon(PxArt.wsSkin)
    val wardrobeCape = MillidaIcon(PxArt.wsCape)
    val wardrobeHead = MillidaIcon(PxArt.wsHead)
    val wardrobeBody = MillidaIcon(PxArt.wsBody)
    val wardrobeBack = MillidaIcon(PxArt.wsBack)
    val wardrobePet = MillidaIcon(PxArt.wsPet)
    val wardrobeEmote = MillidaIcon(PxArt.wsEmote)

    val home: MillidaIcon get() = blocks
    val grassBlock: MillidaIcon get() = blocks
    val catalog: MillidaIcon get() = book
    val library: MillidaIcon get() = chest
    val character: MillidaIcon get() = looks
    val shop: MillidaIcon get() = emerald
    val friends: MillidaIcon get() = users
    val ruby: MillidaIcon get() = gem
    val barrier: MillidaIcon get() = ban

    private val byDesktopIdCache = ConcurrentHashMap<String, MillidaIcon>()

    fun byDesktopId(id: String): MillidaIcon? {
        val cells = PxArt.byId[id] ?: return null
        return byDesktopIdCache.getOrPut(id) { MillidaIcon(cells) }
    }
}

@Composable
fun MIcon(
    icon: MillidaIcon,
    size: Dp = 18.dp,
    color: Color = millida.fg,
    modifier: Modifier = Modifier,
) {
    val tint = color.takeOrElse { LocalContentColor.current }
    Canvas(modifier.size(size)) { drawPixelGlyph(icon, tint) }
}

private fun DrawScope.drawPixelGlyph(icon: MillidaIcon, tint: Color) {
    val grid = icon.side
    val fit = min(size.width, size.height) / grid
    if (fit <= 0f) return
    val cell = if (fit >= 1f) floor(fit + PX_SNAP_BIAS) else fit
    val left = floor((size.width - cell * grid) / 2f)
    val top = floor((size.height - cell * grid) / 2f)
    val soft = tint.copy(alpha = tint.alpha * PX_SOFT_ALPHA)
    val runs = icon.runs
    var index = 0
    while (index < runs.size) {
        val fill = when (val code = runs[index + 3]) {
            PX_TINT -> tint
            PX_TINT_SOFT -> soft
            else -> PxArt.palette[code]
        }
        drawRect(
            color = fill,
            topLeft = Offset(left + runs[index] * cell, top + runs[index + 1] * cell),
            size = Size(runs[index + 2] * cell, cell),
        )
        index += PX_RUN_FIELDS
    }
}
