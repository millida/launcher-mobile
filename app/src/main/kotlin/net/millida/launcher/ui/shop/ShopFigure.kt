package net.millida.launcher.ui.shop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.splitVariantCode
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.SkinPose
import net.millida.launcher.ui.SkinStage
import net.millida.launcher.ui.character.CosmeticsModel
import net.millida.launcher.ui.character.StageOutfit
import net.millida.launcher.ui.character.buildStageOutfit
import net.millida.launcher.ui.mixSrgb
import kotlin.math.cos
import kotlin.math.sin

/** Who the shop dresses: the player's own figure and the wardrobe that knows the items' models. */
internal class ShopFigure(val skinUrl: String?, val slim: Boolean, val cosmetics: CosmeticsModel)

internal val LocalShopFigure = staticCompositionLocalOf<ShopFigure?> { null }

/** Pets fly off the frame and flat things have no model: they stay pictures (Today.tsx FLAT). */
private val FLAT_SLOTS = setOf("PET", "SPRAY", "BANNER", "ICON")

private enum class ShotFocus(val scale: Float, val lift: Float) {
    Full(1f, 0f),
    Body(1.2f, 0.06f),
    Head(2.3f, 0.62f),
    Upper(1.6f, 0.3f),
    Legs(1.6f, -0.32f),
}

private fun focusOf(slot: String): ShotFocus = when (slot.uppercase()) {
    "HAT", "HEAD", "FACE", "EARS" -> ShotFocus.Head
    "SHOES", "FEET", "PANTS", "SKIRT" -> ShotFocus.Legs
    "ARMS", "HAND", "SHOULDERS", "SHOULDER", "ACCESSORY" -> ShotFocus.Upper
    "BACK", "WINGS", "CAPE", "PET" -> ShotFocus.Full
    else -> ShotFocus.Body
}

private fun CosmeticsModel.catalogItem(code: String, items: (String) -> CosmeticItem?): CosmeticItem? =
    items(code) ?: items(splitVariantCode(code).first)

/** The shop's scene behind a figure (shop2.css .sv-scene): the tone over a dark floor, thin rays and a glow from the feet. */
@Composable
internal fun ShotScene(tone: Color, modifier: Modifier = Modifier, feet: Float = 0.84f) {
    Canvas(modifier) {
        drawRect(tone.mixSrgb(MillidaPixel.revealCenter, 0.36f))
        val c = Offset(size.width / 2f, size.height * (feet - 0.34f))
        val r = size.width * 1.3f
        val path = Path()
        var a = 4f
        while (a < 364f) {
            val from = Math.toRadians((a - 90f).toDouble())
            val to = Math.toRadians((a + 7f - 90f).toDouble())
            path.moveTo(c.x, c.y)
            path.lineTo(c.x + r * cos(from).toFloat(), c.y + r * sin(from).toFloat())
            path.lineTo(c.x + r * cos(to).toFloat(), c.y + r * sin(to).toFloat())
            path.close()
            a += 18f
        }
        drawPath(path, tone.copy(alpha = 0.34f * 0.55f))
        val glow = Offset(size.width / 2f, size.height * (feet - 0.3f))
        drawRect(
            Brush.radialGradient(
                0f to tone.mixSrgb(MillidaPixel.white, 0.6f).copy(alpha = 0.5f),
                0.22f to tone.copy(alpha = 0.2f),
                0.52f to tone.copy(alpha = 0f),
                center = glow,
                radius = maxOf(size.width, size.height),
            ),
        )
    }
}

/**
 * Items on the player, the desktop's ItemShot/SetShot: the own skin dressed in
 * the item's models on the shop's scene, framed on the part of the body the
 * item sits on. Until the wardrobe knows the models — or for a flat item — the
 * [fallback] picture stands.
 */
@Composable
internal fun PlayerShot(
    items: List<RubyItem>,
    tone: Color,
    modifier: Modifier = Modifier,
    flatEffects: Boolean = false,
    fallback: @Composable BoxScope.() -> Unit,
) {
    val figure = LocalShopFigure.current
    val shown = items.filter { val slot = it.slot.uppercase(); slot !in FLAT_SLOTS && !(flatEffects && slot == "EFFECT") }
    val focus = if (items.size == 1) focusOf(items.first().slot) else ShotFocus.Full
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        ShotScene(tone, Modifier.fillMaxSize(), feet = if (focus == ShotFocus.Full || focus == ShotFocus.Body) 0.86f else 1.2f)
        if (figure == null || shown.isEmpty()) {
            fallback()
            return@Box
        }
        val cosmetics by figure.cosmetics.state.collectAsStateWithLifecycle()
        val dressed = shown.mapNotNull { item -> figure.cosmetics.catalogItem(item.code) { cosmetics.catalog.byId(it) } }
        val key = dressed.joinToString(",") { it.id }
        val outfit by produceState<StageOutfit?>(null, key, figure.slim) {
            value = if (dressed.isEmpty()) {
                null
            } else {
                try {
                    buildStageOutfit(dressed, figure.slim, figure.cosmetics::model).first.takeIf { !it.isEmpty }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    null
                }
            }
        }
        val dressedOutfit = outfit
        if (dressedOutfit == null) {
            fallback()
        } else {
            SkinStage(
                skinUrl = figure.skinUrl,
                slim = figure.slim,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = focus.scale
                        scaleY = focus.scale
                        translationY = size.height * focus.lift
                    },
                pose = SkinPose.Still,
                interactive = false,
                outfit = dressedOutfit,
                snapshotKey = "${figure.skinUrl}|$key",
            )
        }
    }
}
