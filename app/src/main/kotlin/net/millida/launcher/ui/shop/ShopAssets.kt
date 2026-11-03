package net.millida.launcher.ui.shop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.SkinFigure
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import java.util.concurrent.ConcurrentHashMap

private val ASSETS = ConcurrentHashMap<String, ImageBitmap>()
private const val NICK_FLIP_MS = 2000L

/** A picture shipped from the desktop launcher's public art (scripts/gen_shop_assets.ts), decoded once per run. */
@Composable
internal fun ShopAsset(path: String, size: Dp, modifier: Modifier = Modifier, pixelated: Boolean = false) {
    ShopAssetBox(path, modifier.size(size), pixelated)
}

@Composable
internal fun ShopAssetBox(path: String, modifier: Modifier, pixelated: Boolean = false, scale: ContentScale = ContentScale.Fit) {
    val context = LocalContext.current
    val image = remember(path) {
        ASSETS[path] ?: runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }
            .getOrNull()?.asImageBitmap()?.also { ASSETS[path] = it }
    }
    if (image == null) {
        Box(modifier)
        return
    }
    Image(
        image,
        contentDescription = null,
        modifier = modifier,
        contentScale = scale,
        filterQuality = if (pixelated) FilterQuality.None else FilterQuality.Low,
    )
}

private val PACK_ART = listOf("handful", "pouch", "purse", "casket", "chests", "hoard", "trove", "treasury", "vault")

/** The picture of a pack by its place in the row, small to large: the service's codes change, the order does not. */
internal fun packArtAt(index: Int, count: Int): String {
    val at = if (count < 2) 0 else Math.round(index * (PACK_ART.size - 1).toFloat() / (count - 1))
    return "packs/" + PACK_ART[at.coerceIn(0, PACK_ART.size - 1)] + ".webp"
}

internal const val STARTER_ART = "packs/starter.webp"
internal const val MONTH_ART = "packs/vault.webp"
internal const val PLUS_RUBIES_ART = "packs/treasury.webp"

private val LAUNCHER_BADGE = listOf("#####.##", "#.....##", "#.......", "#..##..#", "#..##..#", "#......#", "#......#", "########")
private val PLUS_BADGE = listOf("#####.##", "#.....##", "#.....##", "#..##..#", "#..##..#", "#......#", "#......#", "########")

/** The mod's badge before a nick (millida-mod BadgeMark): green for the launcher, the same mark with a closed corner in gold for PLUS. */
@Composable
internal fun BadgeGlyph(plus: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val rows = if (plus) PLUS_BADGE else LAUNCHER_BADGE
    val ink = if (plus) MillidaPixel.badgeGold else MillidaPixel.badgeGreen
    val shade = if (plus) MillidaPixel.badgeGoldShade else MillidaPixel.badgeGreenShade
    Canvas(modifier.size(size)) {
        val cell = this.size.width / 9f
        for (pass in 0..1) {
            val color = if (pass == 0) shade else ink
            val shift = if (pass == 0) cell else 0f
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, c ->
                    if (c == '#') drawRect(color, Offset(x * cell + shift, y * cell + shift), Size(cell, cell))
                }
            }
        }
    }
}

/** A Minecraft name tag: the translucent black plate, the game font, the badge before the nick. */
@Composable
internal fun NickTag(nick: String, plus: Boolean, fontSize: Float = 14f, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Row(
        modifier
            .background(MillidaPixel.nameTagBack)
            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BadgeGlyph(plus, (fontSize).dp)
        Text(
            nick,
            style = MillidaType.pixel.copy(
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.15f).sp,
                shadow = Shadow(if (plus) MillidaPixel.badgeGoldShade else MillidaPixel.nameTagShade, Offset(2f * density, 2f * density), 0f),
            ),
            color = if (plus) MillidaPixel.nickGold else MillidaPixel.white,
            maxLines = 1,
        )
    }
}

/** One name tag for the PLUS tile: the badge flips from green «без PLUS» to gold «с PLUS» and back (StatusCompare.tsx NickFlip). */
@Composable
internal fun NickFlip(nick: String, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    var plus by remember { mutableStateOf(false) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        while (true) {
            delay(NICK_FLIP_MS)
            plus = !plus
        }
    }
    val face: @Composable (Boolean) -> Unit = { on ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (on) tr("с PLUS") else tr("без PLUS"),
                style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Black),
                color = if (on) MillidaPixel.nickGold else MillidaPixel.badgeGreen,
                maxLines = 1,
            )
            NickTag(nick, on)
        }
    }
    if (reduced) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            face(false)
            face(true)
        }
    } else {
        Box(modifier) { face(plus) }
    }
}

/** «Как тебя видят в игре»: the player's own figure twice, an ordinary name tag and the gold one (StatusCompare.tsx, row). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusCompare(nick: String, skinUrl: String?, slim: Boolean, modifier: Modifier = Modifier) {
    val colors = millida
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        for (plus in listOf(false, true)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier
                        .width(150.dp)
                        .pixelSurface(colors.inset, MillidaCut.plate)
                        .drawBehind {
                            if (plus) {
                                drawRect(
                                    Brush.radialGradient(
                                        0f to MillidaPixel.plusGlow,
                                        0.7f to MillidaPixel.plusGlow.copy(alpha = 0f),
                                        center = Offset(size.width / 2f, size.height * 0.6f),
                                        radius = size.width * 0.6f,
                                    ),
                                )
                            }
                        }
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NickTag(nick, plus, fontSize = 13f)
                    SkinFigure(url = skinUrl, slim = slim, modifier = Modifier.height(80.dp).aspectRatio(0.5f), background = Color.Transparent)
                }
                Text(
                    if (plus) tr("С PLUS") else tr("Обычные игроки"),
                    style = MillidaType.small.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (plus) MillidaPixel.nickGold else colors.fgSubtle,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            tr("Золотой знак у ника в игре"),
            modifier = Modifier.width(140.dp).align(Alignment.CenterVertically),
            style = MillidaType.body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
            color = MillidaPixel.nickGoldSoft,
        )
    }
}
