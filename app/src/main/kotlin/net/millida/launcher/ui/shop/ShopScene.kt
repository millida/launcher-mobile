package net.millida.launcher.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.millida.launcher.ChestOpening
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.RevealCard
import net.millida.launcher.RewardKind
import net.millida.launcher.RewardReveal
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trPlural
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.rubies.PendingChest
import net.millida.launcher.ui.DropWindowDim
import net.millida.launcher.ui.ExtendWindowUnderCutout
import net.millida.launcher.ui.HideSystemBarsWhenWide
import net.millida.launcher.ui.rememberWindowOverflow
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRarityPlate
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.drawRays
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.steppedPath
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val FIRST_CARD_MS = 260L
private const val BEAT_MS = 460L
private const val PLOP_MS = 520
private const val GLOW_MS = 1100
private const val CARD_FLASH_MS = 500
private const val FRAGMENT_CELLS = 10
private const val FRAGMENT_STEP_MS = 160
private const val FRAGMENT_FILL_MS = 360
private const val RAY_SPIN_PER_S = 0.25f

private val CARD_WIDTH = 136.dp

/** A chest of the bonus, the pass, playtime or a gift: the shared opening, its hits from the answer once known. */
@Composable
internal fun ChestScene(
    opening: ChestOpening,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onFail: (String) -> Unit,
    onWardrobe: () -> Unit,
    onRetry: () -> Unit,
    topUp: ChestTopUp? = null,
) {
    key(opening.serial) {
        val tier = opening.chest.tier
        val result = opening.result
        val opens = remember(result) { result?.let { listOf(RevealOpen(opening.cards, OPEN_ITEM, null)) } }
        val hits = result?.hits?.takeIf { it > 0 }?.coerceIn(1, 6) ?: chestHits(tier)
        CaseReveal(
            title = chestTitle(tier),
            tier = tier,
            tint = null,
            color = chestTone(tier),
            hits = hits,
            floor = null,
            deal = false,
            asked = opening.started,
            attempt = opening.attempt,
            opens = opens,
            error = opening.error,
            onBegin = onStart,
            onRetry = onRetry,
            onFail = onFail,
            onFinished = {},
            onClose = onClose,
            onWear = onWardrobe,
            again = if (opening.left > 0) RevealAgain(tr("Следующий · ещё {left}", opening.left), null, true, onNext) else null,
            extra = topUp?.let { up -> { card, _ -> ChestTopUpButton(card, up) } },
        )
    }
}

/** "Докупить" under a fragments card of the summary: the started item is finished only by buying the rest. */
internal class ChestTopUp(
    val done: Set<String>,
    val busy: (String) -> Boolean,
    val onTopUp: (RevealCard) -> Unit,
)

@Composable
private fun ChestTopUpButton(card: RevealCard, topUp: ChestTopUp) {
    val item = card.item ?: return
    if (card.kind != RewardKind.FRAGMENTS || card.topUp <= 0) return
    if (item.code in topUp.done) {
        MBadge(tr("Твоя"), color = MillidaPixel.white, background = MillidaPixel.glintSoft, icon = MIcons.check)
    } else {
        MSecondaryButton(
            tr("Докупить {0}", groupDigits(card.topUp)),
            onClick = { topUp.onTopUp(card) },
            icon = MIcons.ruby,
            size = MButtonSize.Small,
            loading = topUp.busy("fragments:" + item.code),
        )
    }
}

private fun cardRank(card: RevealCard): Int = when (card.kind) {
    RewardKind.ITEM, RewardKind.FRAGMENTS -> rarityIndex(Rarity.of(card.rarity) ?: Rarity.of(card.item?.rarity))
    RewardKind.CHEST -> rarityIndex(chestRarity(card.tier))
    else -> 1
}

@Composable
internal fun SceneWindow(onBack: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DropWindowDim()
        ExtendWindowUnderCutout()
        HideSystemBarsWhenWide()
        val overflow = rememberWindowOverflow()
        Box(
            Modifier
                .fillMaxSize()
                .background(MillidaPixel.sceneScrim)
                .padding(bottom = overflow),
            content = content,
        )
    }
}

private class PlopFrame(val y: Float, val scale: Float, val alpha: Float)

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

private fun plopAt(progress: Float): PlopFrame {
    val ease = MillidaMotion.plop
    return when {
        progress >= 1f -> PlopFrame(0f, 1f, 1f)
        progress < 0.55f -> {
            val t = ease.transform(progress / 0.55f)
            PlopFrame(lerp(-160f, 10f, t), lerp(0.2f, 1.14f, t), t.coerceIn(0f, 1f))
        }
        progress < 0.78f -> {
            val t = ease.transform((progress - 0.55f) / 0.23f)
            PlopFrame(lerp(10f, 0f, t), lerp(1.14f, 0.95f, t), 1f)
        }
        else -> {
            val t = ease.transform((progress - 0.78f) / 0.22f)
            PlopFrame(0f, lerp(0.95f, 1f, t), 1f)
        }
    }
}

private fun glowSpread(progress: Float): Float = when {
    progress < 0.35f -> 22f * progress / 0.35f
    else -> 22f - 14f * (progress - 0.35f) / 0.65f
}

private fun glowAlpha(progress: Float): Float = when {
    progress < 0.35f -> 0.5f * progress / 0.35f
    else -> 0.5f - 0.22f * (progress - 0.35f) / 0.65f
}

@Composable
internal fun RevealCardView(card: RevealCard, modifier: Modifier = Modifier) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val tone = card.tone()
    val plop = remember { Animatable(if (reduced) 1f else 0f) }
    val glow = remember { Animatable(if (reduced) 1f else 0f) }
    val flash = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            launch { glow.animateTo(1f, tween(GLOW_MS, easing = LinearEasing)) }
            launch { flash.animateTo(1f, tween(CARD_FLASH_MS, easing = MillidaMotion.steps(4))) }
            plop.animateTo(1f, tween(PLOP_MS, easing = LinearEasing))
        }
    }
    val rarity = when (card.kind) {
        RewardKind.ITEM, RewardKind.FRAGMENTS -> Rarity.of(card.rarity) ?: Rarity.of(card.item?.rarity)
        RewardKind.CHEST -> chestRarity(card.tier)
        else -> null
    }
    Box(
        modifier
            .width(CARD_WIDTH)
            .graphicsLayer {
                val frame = plopAt(plop.value)
                translationY = frame.y * density
                scaleX = frame.scale
                scaleY = frame.scale
                alpha = frame.alpha
            }
            .drawBehind {
                val g = glow.value
                val spread = floor(glowSpread(g).dp.toPx())
                val alpha = if (reduced) 0.28f else glowAlpha(g)
                if (alpha <= 0f || spread <= 0f) return@drawBehind
                val step = MillidaCut.plate.toPx()
                translate(-spread, -spread) {
                    drawPath(steppedPath(size.width + spread * 2f, size.height + spread * 2f, step, step), tone.copy(alpha = alpha * 0.45f))
                }
                val inner = floor(spread / 2f)
                translate(-inner, -inner) {
                    drawPath(steppedPath(size.width + inner * 2f, size.height + inner * 2f, step, step), tone.copy(alpha = alpha))
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 176.dp)
                .pixelSurface(
                    fill = tone.mixSrgb(colors.surface, 0.16f),
                    cut = MillidaCut.plate,
                    frame = PixelFrame(
                        outline = tone,
                        outlineWidth = 3.dp,
                        top = MillidaPixel.glintSoft,
                        topWidth = 3.dp,
                        bottom = MillidaPixel.tileBottom,
                        bottomWidth = 7.dp,
                    ),
                )
                .drawBehind {
                    val shown = 0.85f * (1f - flash.value)
                    if (shown > 0f) drawRect(tone.copy(alpha = shown))
                }
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (card.plus) PlusBadge()
            RewardGlyph(card.kind, side = 72.dp, tier = card.tier, item = card.item)
            CardTitle(card)
            if (rarity != null && card.kind != RewardKind.CHEST) MRarityPlate(rarity)
            CardFoot(card, tone)
        }
    }
}

@Composable
private fun CardTitle(card: RevealCard) {
    val colors = millida
    val style = MillidaType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.Black, lineHeight = 18.sp)
    when (card.kind) {
        RewardKind.RUBIES, RewardKind.SHARDS -> {
            val count by rememberCounter(card.amount, start = 0)
            Text("+" + net.millida.launcher.ui.groupDigits(count), style = MillidaType.pixelLarge, color = colors.fg, maxLines = 1)
            Text(
                if (card.kind == RewardKind.RUBIES) rubyWord(card.amount) else shardWord(card.amount),
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 1,
            )
        }
        RewardKind.PLUS -> Text(tr("PLUS открыт"), style = style, color = colors.fg, textAlign = TextAlign.Center, maxLines = 1)
        RewardKind.STARS -> Text(tr("Путь пройден"), style = style, color = colors.fg, textAlign = TextAlign.Center, maxLines = 1)
        RewardKind.CREATOR -> Text(
            tr("Ты поддерживаешь {name}", card.item?.name.orEmpty()),
            style = style,
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RewardKind.CHEST -> Text(
            chestTitle(card.tier),
            style = style,
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RewardKind.FRAGMENTS -> Text(
            card.item?.name?.takeIf { it.isNotBlank() } ?: "+${card.shards} ${shardWord(card.shards)}",
            style = style,
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        else -> Text(
            card.item?.name?.takeIf { it.isNotBlank() } ?: tr("Вещь"),
            style = style,
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardFoot(card: RevealCard, tone: Color) {
    val colors = millida
    when {
        card.kind == RewardKind.FRAGMENTS && card.item == null -> Text(
            trPlural(card.amount, tr("{amount} фрагмент → осколки", card.amount), tr("{amount} фрагмента → осколки", card.amount), tr("{amount} фрагментов → осколки", card.amount)),
            style = MillidaType.small,
            color = colors.fgSubtle,
            textAlign = TextAlign.Center,
        )
        card.kind == RewardKind.FRAGMENTS && card.need > 0 -> {
            FragmentCells(have = card.have, need = card.need, gain = card.gain, completed = card.completed, tone = tone)
            Text(
                if (card.completed) tr("Собрано!") else "+${card.gain} · ${card.have.coerceAtMost(card.need)}/${card.need}",
                style = MillidaType.pixelSmall,
                color = if (card.completed) tone else colors.fgSubtle,
                maxLines = 1,
            )
        }
        card.kind == RewardKind.FRAGMENTS -> Text(
            "+${card.amount} ${fragmentWord(card.amount)}",
            style = MillidaType.small,
            color = colors.fgSubtle,
            textAlign = TextAlign.Center,
        )
        card.kind == RewardKind.ITEM && card.duplicate -> Text(
            trPlural(card.shards, tr("Повтор: +{shards} осколок", card.shards), tr("Повтор: +{shards} осколка", card.shards), tr("Повтор: +{shards} осколков", card.shards)),
            style = MillidaType.small,
            color = colors.fgSubtle,
            textAlign = TextAlign.Center,
        )
        card.kind == RewardKind.ITEM -> Text(tr("Навсегда"), style = MillidaType.small, color = colors.fgSubtle)
        card.kind == RewardKind.PLUS -> Text(tr("Вторая награда дня"), style = MillidaType.small, color = colors.fgSubtle, textAlign = TextAlign.Center)
        card.kind == RewardKind.STARS -> Text(tr("Забирай вещь"), style = MillidaType.small, color = colors.fgSubtle, textAlign = TextAlign.Center)
        card.kind == RewardKind.CREATOR -> Text(
            tr("Часть с твоих покупок — автору"),
            style = MillidaType.small,
            color = colors.fgSubtle,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun FragmentCells(have: Int, need: Int, gain: Int, completed: Boolean, tone: Color, modifier: Modifier = Modifier) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    val total = max(1, need)
    val after = if (completed) FRAGMENT_CELLS else (have.coerceIn(0, total) * FRAGMENT_CELLS / total)
    val before = ((have - gain).coerceIn(0, total) * FRAGMENT_CELLS / total).coerceAtMost(after)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (index in 0 until FRAGMENT_CELLS) {
            val fresh = index in before until after
            FragmentCell(
                on = index < after,
                fresh = fresh && !reduced,
                delayMs = 200 + (index - before) * FRAGMENT_STEP_MS,
                tone = tone,
                off = colors.fg.copy(alpha = 0.12f),
            )
        }
    }
}

@Composable
private fun FragmentCell(on: Boolean, fresh: Boolean, delayMs: Int, tone: Color, off: Color) {
    val fill = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(fresh) {
        if (fresh) fill.animateTo(1f, tween(FRAGMENT_FILL_MS, delayMillis = delayMs, easing = MillidaMotion.steps(3)))
    }
    Box(
        Modifier
            .size(width = 9.dp, height = 8.dp)
            .graphicsLayer {
                val grow = fresh && fill.value > 0.3f && fill.value < 0.6f
                scaleX = if (grow) 1.3f else 1f
                scaleY = if (grow) 1.3f else 1f
            }
            .drawBehind {
                val value = fill.value
                val color = when {
                    !on -> off
                    !fresh -> tone
                    value < 0.3f -> off
                    value < 0.6f -> MillidaPixel.white
                    else -> tone
                }
                drawRect(color)
                drawRect(MillidaPixel.tileBottom, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
            },
    )
}

private sealed interface Beat {
    data class Card(val index: Int) : Beat
    data object PlusWave : Beat
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RewardScene(reveal: RewardReveal, onDone: () -> Unit, onOpenChests: (List<PendingChest>) -> Unit) {
    key(reveal.serial) {
        RewardSceneBody(reveal, onDone, onOpenChests)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RewardSceneBody(reveal: RewardReveal, onDone: () -> Unit, onOpenChests: (List<PendingChest>) -> Unit) {
    val reduced = LocalReducedMotion.current
    val feedback = LocalUiFeedback.current
    val beats = remember(reveal) {
        buildList<Beat> {
            val free = reveal.cards.indices.filter { !reveal.cards[it].plus }
            val plus = reveal.cards.indices.filter { reveal.cards[it].plus }
            free.forEach { add(Beat.Card(it)) }
            if (plus.isNotEmpty()) {
                add(Beat.PlusWave)
                plus.forEach { add(Beat.Card(it)) }
            }
        }
    }
    var shown by remember { mutableIntStateOf(if (reduced) beats.size else 0) }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        if (reduced) {
            feedback.play(UiSoundEvent.Success)
            return@LaunchedEffect
        }
        val startedAt = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            clock.longValue = now - startedAt
            val due = if (clock.longValue < FIRST_CARD_MS) 0 else min(beats.size, 1 + ((clock.longValue - FIRST_CARD_MS) / BEAT_MS).toInt())
            while (shown < due) {
                when (val beat = beats[shown]) {
                    Beat.PlusWave -> feedback.play(UiSoundEvent.Notify)
                    is Beat.Card -> feedback.play(if (reveal.cards[beat.index].plus) UiSoundEvent.Achievement else UiSoundEvent.Success)
                }
                shown += 1
            }
        }
    }
    val finished = shown >= beats.size
    val best = reveal.cards.maxByOrNull { cardRank(it) }?.tone() ?: MillidaPixel.gold
    SceneWindow(onBack = {
        if (finished) {
            onDone()
        } else {
            shown = beats.size
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.4f)
            val spin = if (reduced) 0f else clock.longValue / 1000f * RAY_SPIN_PER_S
            drawRays(center, max(size.width, size.height) * 0.7f, best.copy(alpha = 0.14f), rotation = spin)
        }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    if (!finished) {
                        shown = beats.size
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(reveal.kicker.uppercase(), style = MillidaType.caption, color = best, textAlign = TextAlign.Center)
            val visible = beats.take(shown)
            val freeCards = visible.filterIsInstance<Beat.Card>().filter { !reveal.cards[it.index].plus }
            val plusCards = visible.filterIsInstance<Beat.Card>().filter { reveal.cards[it.index].plus }
            if (freeCards.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (beat in freeCards) key(beat.index) { RevealCardView(reveal.cards[beat.index]) }
                }
            }
            if (Beat.PlusWave in visible) {
                PlusBadge()
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (beat in plusCards) key(beat.index) { RevealCardView(reveal.cards[beat.index]) }
                }
            }
            if (finished) {
                if (reveal.chests.isNotEmpty()) {
                    MPrimaryButton(
                        text = if (reveal.chests.size > 1) tr("Открыть сундуки ({size})", reveal.chests.size) else tr("Открыть сундук"),
                        onClick = { onOpenChests(reveal.chests) },
                        size = MButtonSize.Large,
                        icon = MIcons.chest,
                    )
                    MTextButton(tr("Позже"), onClick = onDone, color = MillidaPixel.whiteSoft)
                } else {
                    MPrimaryButton(tr("Готово"), onClick = onDone, size = MButtonSize.Large)
                }
            }
        }
    }
}

/** The newcomer gift comes on whatever screen the app opens on, like the desktop splash. */
@Composable
fun ShopWelcome(model: LauncherViewModel) {
    val shop by model.shop.state.collectAsStateWithLifecycle()
    val welcome = shop.welcome ?: return
    RewardScene(reveal = welcome, onDone = { model.shop.dismissWelcome(welcome.serial) }, onOpenChests = { model.shop.dismissWelcome(welcome.serial) })
}
