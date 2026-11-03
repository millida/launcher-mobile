package net.millida.launcher.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.RevealCard
import net.millida.launcher.RewardKind
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.GeistFamily
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRarityPlate
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.shop.chest.Chest3D
import net.millida.launcher.ui.shop.chest.ChestMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val BURST_MS = 650L
private const val SLOW_ANSWER_MS = 8000L
private const val RISE_HOLD_MS = 560L

private enum class Phase { Hit, Burst, Card, Deal, Sum }

/** The summary's second button: «Ещё раз ◆690», «Следующий · ещё 2». */
internal class RevealAgain(val label: String, val icon: MillidaIcon?, val primary: Boolean, val onClick: () -> Unit)

private val CRACKS: List<List<List<Float>>> = listOf(
    listOf(listOf(50f, 50f, 44f, 44f, 46f, 38f, 40f, 32f), listOf(50f, 50f, 58f, 46f, 62f, 48f)),
    listOf(listOf(50f, 50f, 36f, 52f, 30f, 48f, 22f, 52f), listOf(50f, 50f, 60f, 56f, 66f, 54f, 74f, 60f), listOf(50f, 50f, 52f, 40f, 58f, 34f)),
    listOf(
        listOf(50f, 50f, 40f, 62f, 42f, 70f, 34f, 78f),
        listOf(50f, 50f, 64f, 40f, 72f, 42f, 80f, 34f),
        listOf(50f, 50f, 26f, 40f, 18f, 42f, 10f, 36f),
        listOf(50f, 50f, 56f, 66f, 62f, 74f, 60f, 84f),
    ),
)

private val CRACKS_MORE: List<List<Float>> = listOf(
    listOf(50f, 50f, 30f, 30f, 34f, 22f, 26f, 12f),
    listOf(50f, 50f, 70f, 30f, 66f, 20f, 76f, 10f),
    listOf(50f, 50f, 22f, 62f, 14f, 60f, 6f, 70f),
    listOf(50f, 50f, 78f, 64f, 88f, 62f, 96f, 72f),
    listOf(50f, 50f, 46f, 74f, 50f, 84f, 44f, 96f),
)

private fun heavy(size: Float, line: Float = size * 1.15f) = TextStyle(
    fontFamily = GeistFamily,
    fontWeight = FontWeight.Black,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private fun RevealCard.isCurrency(): Boolean = item == null || kind == RewardKind.RUBIES || kind == RewardKind.SHARDS

/** A fragment or item card without its item is the shards it turned into. */
private fun RevealCard.cashAmount(): Long = when (kind) {
    RewardKind.RUBIES, RewardKind.SHARDS -> amount
    else -> if (shards > 0) shards else amount
}

private fun RevealCard.revealTone(): Color = when {
    item != null && kind != RewardKind.RUBIES && kind != RewardKind.SHARDS -> rarityOf()?.color ?: Rarity.EPIC.color
    kind == RewardKind.RUBIES -> MillidaPixel.rubyIcon
    else -> Rarity.RARE.color
}

private fun RevealCard.cashWord(): String = if (kind == RewardKind.RUBIES) rubyWord(cashAmount()) else shardWord(cashAmount())

/**
 * The opening of every chest and case, the desktop's CaseReveal: a huge 3D
 * chest with rays of its colour; each tap is a hit — squash, freeze, sparks,
 * new cracks of light; the last one bursts it, the rewards come out one by one,
 * then the summary. Shop cases climb from their guarantee to the rarity the
 * service already rolled; ×10 deals ten cards face down. The request goes out
 * on the first hit (a paid case sends it at once) and its answer waits for the
 * last hit; after 15 s without it the same request can be sent again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaseReveal(
    title: String,
    tier: String,
    tint: Int?,
    color: Color,
    hits: Int,
    floor: Rarity?,
    deal: Boolean,
    asked: Boolean,
    attempt: Int,
    opens: List<RevealOpen>?,
    error: String?,
    onBegin: () -> Unit,
    onRetry: () -> Unit,
    onFail: (String) -> Unit,
    onFinished: () -> Unit,
    onClose: () -> Unit,
    onWear: () -> Unit,
    again: RevealAgain?,
    extra: (@Composable (RevealCard, Int) -> Unit)? = null,
) {
    val calm = LocalReducedMotion.current
    val feedback = LocalUiFeedback.current
    val scope = rememberCoroutineScope()
    val need = if (deal) max(hits, 4) else hits
    var phase by remember { mutableStateOf(Phase.Hit) }
    var hitCount by remember { mutableIntStateOf(0) }
    var kick by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableIntStateOf(0) }
    var late by remember { mutableStateOf(false) }
    var ups by remember { mutableIntStateOf(0) }
    var bursting by remember { mutableStateOf(false) }
    var flipAllRunning by remember { mutableStateOf(false) }
    val kickAnim = remember { Animatable(1f) }
    var kickSign by remember { mutableStateOf(1f) }
    val shake = remember { Animatable(0f) }
    var shakeAmp by remember { mutableStateOf(0f) }
    val pop = remember { Animatable(1f) }
    val latestFail by rememberUpdatedState(onFail)
    val answered = opens != null
    val rewards = opens?.firstOrNull()?.cards.orEmpty()

    LaunchedEffect(Unit) { if (!calm) { delay(260); feedback.chestHit(0) } }
    LaunchedEffect(asked, attempt, answered) {
        late = false
        if (asked && !answered) {
            delay(ANSWER_TIMEOUT_MS)
            late = true
        }
    }
    LaunchedEffect(error) { if (error != null && !answered) { feedback.play(UiSoundEvent.Error); latestFail(error) } }

    val ladder = remember(floor, opens) { floor?.let { revealLadder(it, opens?.let(::finalRarity)) }.orEmpty() }
    val step = if (ladder.isEmpty()) 0 else ladderAt(ladder, if (answered) hitCount else min(hitCount, 0), need)
    val lvl = ladder.getOrNull(step)
    val lvlTone = lvl?.color ?: color
    var prevStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(step) {
        if (step > prevStep && phase == Phase.Hit) {
            ups += 1
            feedback.play(UiSoundEvent.ChestRare)
        }
        prevStep = step
    }

    val order = remember(opens, deal) { if (opens != null && deal) dealOrder(opens) else emptyList() }

    LaunchedEffect(opens, hitCount) {
        if (opens == null || hitCount < need || phase != Phase.Hit || bursting) return@LaunchedEffect
        if (opens.isEmpty() || opens.first().cards.isEmpty()) {
            latestFail(tr("Сундук пуст — попробуй позже"))
            return@LaunchedEffect
        }
        bursting = true
        val rises = ladder.size > 1
        scope.launch {
            if (!calm && rises) delay(RISE_HOLD_MS)
            phase = Phase.Burst
            feedback.chestBurst()
            if (!calm) {
                launch {
                    pop.snapTo(0f)
                    pop.animateTo(1f, tween(620, easing = EASE_SMOOTH))
                }
                delay(BURST_MS)
            }
            if (phase != Phase.Burst) return@launch
            if (deal) {
                phase = Phase.Deal
                if (calm) flipped = opens.size
            } else {
                phase = Phase.Card
                feedback.chestCard(opens.first().cards.first().revealRank())
            }
        }
    }

    val waiting = phase == Phase.Hit && hitCount >= need
    val view = hitView(hitCount, need, answered, late)
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(waiting, answered) {
        slow = false
        if (waiting && !answered) {
            delay(SLOW_ANSWER_MS)
            slow = true
        }
    }

    val dealDone = deal && opens != null && flipped >= opens.size
    LaunchedEffect(phase == Phase.Sum || dealDone) { if (phase == Phase.Sum || dealDone) onFinished() }

    LaunchedEffect(Unit) {
        if (calm) {
            if (!asked) onBegin()
            hitCount = need
        }
    }

    val hit: () -> Unit = hit@{
        if (phase != Phase.Hit || hitCount >= need) return@hit
        if (!asked) onBegin()
        val before = hitCount
        val last = before + 1 >= need
        hitCount = before + 1
        kick += 1
        feedback.chestHit(min(before, 2) + if (last) 1 else 0)
        kickSign = if (before % 2 == 1) 1f else -1f
        shakeAmp = if (last) 12f else 4f + before * 2f
        scope.launch {
            kickAnim.snapTo(0f)
            kickAnim.animateTo(1f, tween(360, easing = EASE_SMOOTH))
        }
        scope.launch {
            delay(60)
            shake.snapTo(0f)
            shake.animateTo(1f, tween(180, easing = LinearEasing))
            shake.snapTo(0f)
        }
    }
    val next: () -> Unit = next@{
        if (phase != Phase.Card || rewards.isEmpty()) return@next
        if (shown + 1 < rewards.size) {
            shown += 1
            feedback.chestCard(rewards[shown].revealRank())
        } else {
            phase = Phase.Sum
        }
    }
    val flipNext: () -> Unit = flip@{
        val list = opens ?: return@flip
        if (phase != Phase.Deal || flipped >= list.size || flipAllRunning) return@flip
        val o = list[order[flipped]]
        val best = flipped == list.size - 1
        val rank = (o.rarity ?: o.main()?.rarityOf())?.ordinal ?: 0
        feedback.chestCard(if (best || o.kind == OPEN_ITEM) rank else 0)
        flipped += 1
    }
    val flipAll: () -> Unit = all@{
        val list = opens ?: return@all
        if (phase != Phase.Deal || flipAllRunning) return@all
        flipAllRunning = true
        feedback.play(UiSoundEvent.ChestCard)
        scope.launch {
            val total = list.size
            while (flipped < total - 1) {
                delay(110)
                flipped += 1
            }
            if (flipped < total) {
                delay(520)
                val o = list[order[total - 1]]
                feedback.chestCard((o.rarity ?: o.main()?.rarityOf())?.ordinal ?: 0)
                flipped = total
            }
            flipAllRunning = false
        }
    }
    val skip: () -> Unit = skip@{
        val list = opens
        if (list == null) {
            onClose()
            return@skip
        }
        bursting = true
        hitCount = need
        if (deal) {
            phase = Phase.Deal
            flipped = list.size
        } else {
            phase = Phase.Sum
        }
    }
    val back: () -> Unit = {
        if (phase == Phase.Sum || dealDone || (phase == Phase.Hit && !asked)) onClose() else skip()
    }

    val cur = rewards.getOrNull(shown)
    val firstCard = rewards.firstOrNull()
    val flashTone = when {
        phase == Phase.Card && cur != null -> cur.revealTone()
        phase == Phase.Hit || phase == Phase.Burst -> lvlTone
        firstCard != null -> firstCard.revealTone()
        else -> color
    }
    val raysTone = when (phase) {
        Phase.Card, Phase.Sum -> flashTone
        Phase.Hit, Phase.Burst -> if (lvl != null) lvlTone else color
        Phase.Deal -> color
    }
    val glow = min(1f, hitCount.toFloat() / max(1, need))
    val showChest = phase == Phase.Hit || phase == Phase.Burst || phase == Phase.Card
    val density = LocalDensity.current

    SceneWindow(onBack = back) {
        RevealBackdrop(color, Modifier.fillMaxSize())
        RevealRays(raysTone, Modifier.fillMaxSize())
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val a = shakeAmp * density.density
                    val k = when (min(3, (shake.value * 4f).toInt())) {
                        1 -> 0.75f
                        3 -> -0.75f
                        else -> 0f
                    }
                    translationX = a * k
                    translationY = -a * k / 2f
                }
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    when (phase) {
                        Phase.Hit -> hit()
                        Phase.Card -> next()
                        Phase.Deal -> if (!dealDone) flipNext()
                        else -> Unit
                    }
                }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            val areaW = maxWidth
            val areaH = maxHeight
            val side = if (deal) min(areaH * 0.66f, areaW * 0.94f) else min(areaH * 0.56f, areaW * 0.92f)

            if (showChest) {
                ChestStage(
                    side = side,
                    areaW = areaW,
                    areaH = areaH,
                    low = phase == Phase.Card,
                    tier = tier,
                    tint = tint,
                    mode = if (phase == Phase.Hit) (if (hitCount > 0) ChestMode.Shake else ChestMode.Ready) else ChestMode.Open,
                    box = if (lvl != null) lvlTone else color,
                    glow = glow,
                    kick = kickAnim.value,
                    kickSign = kickSign,
                    pop = if (phase == Phase.Burst) pop.value else 1f,
                    cracks = if (phase == Phase.Hit) hitCount else -1,
                    need = need,
                    more = deal,
                ) {
                    if (kick > 0 && phase == Phase.Hit) {
                        key("b$kick") { RevealBurst(12 + hitCount * 6 + step * 10, 160f + hitCount * 40f + step * 30f, kick, lvlTone, Modifier.fillMaxSize()) }
                    }
                    when (phase) {
                        Phase.Hit -> {
                            if (lvl != null && lvl != Rarity.COMMON) {
                                key("l$step") { LevelPlate(lvl, up = step > 0, plus = step == 0 && hitCount < need, Modifier.align(Alignment.TopCenter).padding(top = side * 0.02f)) }
                            }
                            if (view == HitView.Stalled) {
                                Stalled(onRetry, onClose, Modifier.align(Alignment.BottomCenter).offset(y = side * 0.30f))
                            } else {
                                Pips(need, hitCount, waiting, if (lvl != null) lvlTone else color, Modifier.align(Alignment.BottomCenter).offset(y = side * 0.08f))
                                if (view == HitView.Tap) {
                                    key("t$kick") { TapWord(tr("Жми!"), 44f, Modifier.align(Alignment.BottomCenter).offset(y = side * 0.24f)) }
                                } else if (slow) {
                                    Text(
                                        tr("Связь медленная — ждём ответ"),
                                        modifier = Modifier.align(Alignment.BottomCenter).offset(y = side * 0.2f),
                                        style = heavy(14f),
                                        color = MillidaPixel.whiteSoft,
                                    )
                                }
                            }
                        }
                        Phase.Burst -> RevealBurst(if (deal) 72 else 48, if (deal) 640f else 520f, 99, flashTone, Modifier.fillMaxSize())
                        else -> {
                            val left = rewards.size - shown - 1
                            if (left > 0) LeftPlate(left, color, Modifier.align(Alignment.TopEnd).padding(top = side * 0.08f, end = side * 0.1f))
                        }
                    }
                }
            }

            if (phase == Phase.Card && cur != null) {
                key(shown) {
                    OneReward(cur, areaH, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(areaH * 0.78f))
                }
            }

            if (phase == Phase.Deal && opens != null) {
                Deal(opens, order, flipped, dealDone, title, tier, tint, color, again, onClose, onWear, areaH, Modifier.fillMaxSize())
            }

            if (phase == Phase.Sum) {
                Summary(title, rewards, extra, again, onClose, onWear, Modifier.fillMaxSize())
            }

            if (ups > 0 && phase == Phase.Hit) key("u$ups") { RevealFlash(lvlTone, Modifier.fillMaxSize(), 700) }
            if (phase == Phase.Burst) RevealFlash(flashTone, Modifier.fillMaxSize())
            if (phase == Phase.Deal && opens != null && flipped >= opens.size && order.isNotEmpty()) {
                val best = opens[order.last()]
                val bestRank = if (best.kind == OPEN_ITEM) (best.rarity ?: best.main()?.rarityOf())?.ordinal ?: -1 else -1
                if (bestRank >= Rarity.RARE.ordinal) key("best") { RevealFlash(best.main()?.revealTone() ?: color, Modifier.fillMaxSize()) }
            }

            val skipModifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp)
            when {
                phase == Phase.Sum || dealDone -> MIconButton(
                    MIcons.close,
                    onClick = onClose,
                    modifier = skipModifier,
                    tint = MillidaPixel.white,
                    background = MillidaPixel.glintSoft,
                    label = tr("Закрыть"),
                )
                phase == Phase.Deal -> MPrimaryButton(tr("Открыть все"), onClick = flipAll, modifier = skipModifier, size = MButtonSize.Small)
                else -> MSecondaryButton(tr("Пропустить"), onClick = skip, modifier = skipModifier, size = MButtonSize.Small)
            }
        }
    }
}

@Composable
private fun RevealBackdrop(box: Color, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height * 0.46f)
        val r = max(
            max(Offset(0f, 0f).minus(c).getDistance(), Offset(size.width, 0f).minus(c).getDistance()),
            max(Offset(0f, size.height).minus(c).getDistance(), Offset(size.width, size.height).minus(c).getDistance()),
        )
        drawRect(MillidaPixel.revealEdge)
        drawRect(
            Brush.radialGradient(
                0f to box.mixSrgb(MillidaPixel.revealCenter, 0.26f),
                0.62f to MillidaPixel.revealEdge,
                center = c,
                radius = r,
            ),
        )
    }
}

/** The chest on its stage: halo, breathing, the hit squash, gap light and cracks; in the cards it sinks to the bottom. */
@Composable
private fun ChestStage(
    side: Dp,
    areaW: Dp,
    areaH: Dp,
    low: Boolean,
    tier: String,
    tint: Int?,
    mode: ChestMode,
    box: Color,
    glow: Float,
    kick: Float,
    kickSign: Float,
    pop: Float,
    cracks: Int,
    need: Int,
    more: Boolean,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val sink = remember { Animatable(if (low) 1f else 0f) }
    LaunchedEffect(low) { sink.animateTo(if (low) 1f else 0f, tween(if (reduced) 0 else 420, easing = EASE_SMOOTH)) }
    val breathe: State<Float>? = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "breathe").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe-phase",
        )
    }
    val centerY = areaH / 2f
    val lowY = areaH - side / 2f + side * 0.26f
    Box(
        Modifier
            .size(side)
            .offset {
                val s = sink.value
                val y = (centerY + (lowY - centerY) * s - side / 2f).roundToPx()
                IntOffset(((areaW - side) / 2f).roundToPx(), y)
            }
            .graphicsLayer {
                val sc = 1f + (0.46f - 1f) * sink.value
                scaleX = sc
                scaleY = sc
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(side * 0.06f)
                .graphicsLayer {
                    val b = breathe?.value ?: 0f
                    val s = 0.94f + 0.12f * b
                    scaleX = s
                    scaleY = s
                    alpha = 0.45f + glow * 0.55f
                }
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            0f to box.mixSrgb(MillidaPixel.white, 0.7f),
                            0.32f to box.copy(alpha = 0.45f),
                            0.64f to box.copy(alpha = 0f),
                            center = center,
                            radius = size.minDimension / 2f * 1.41421f,
                        ),
                    )
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val b = if (low) 0f else breathe?.value ?: 0f
                    translationY = -10f * density * b
                    val p = pop
                    val popX = keyframes(p, null, 0f, 0.92f, 0.45f, 1.16f, 1f, 1f)
                    val popY = keyframes(p, null, 0f, 1.08f, 0.45f, 1.16f, 1f, 1f)
                    scaleX = (1f + 0.02f * b) * popX
                    scaleY = (1f + 0.02f * b) * popY
                },
        ) {
            Chest3D(
                tier = tier,
                mode = mode,
                tint = tint,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0.8f)
                        val p = kick
                        translationY = keyframes(p, null, 0f, 14f, 0.18f, 14f, 0.48f, -24f, 0.74f, 0f, 1f, 0f) * density
                        scaleX = keyframes(p, null, 0f, 1.12f, 0.18f, 1.12f, 0.48f, 0.94f, 0.74f, 1.02f, 1f, 1f)
                        scaleY = keyframes(p, null, 0f, 0.84f, 0.18f, 0.84f, 0.48f, 1.1f, 0.74f, 0.98f, 1f, 1f)
                        rotationZ = keyframes(p, null, 0f, 0f, 0.18f, 0f, 0.48f, 3f * kickSign, 0.74f, -1f * kickSign, 1f, 0f)
                    },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val leak = if (low) 1f else glow * 0.85f
                        if (leak <= 0f) return@drawBehind
                        val w = size.width * 0.56f
                        val h = size.height * 0.1f
                        val c = Offset(size.width / 2f, size.height * 0.49f)
                        withTransform({ scale(w / h, 1f, c) }) {
                            drawCircle(
                                Brush.radialGradient(
                                    0f to box.mixSrgb(MillidaPixel.white, 0.4f).copy(alpha = leak),
                                    1f to box.copy(alpha = 0f),
                                    center = c,
                                    radius = h / 2f,
                                ),
                                radius = h / 2f,
                                center = c,
                            )
                        }
                    },
            )
            if (cracks >= 0) Cracks(cracks, need, more, box, Modifier.fillMaxSize())
        }
        overlay()
    }
}

@Composable
private fun Cracks(hits: Int, need: Int, more: Boolean, box: Color, modifier: Modifier) {
    val groups = if (more) CRACKS + listOf(CRACKS_MORE) else CRACKS
    val open = if (need > 0) ceil(hits.toFloat() / need * groups.size).toInt() else 0
    val grows = remember(groups.size) { List(groups.size) { Animatable(0f) } }
    LaunchedEffect(open) {
        grows.forEachIndexed { index, anim ->
            if (index < open && anim.value < 1f) launch { anim.animateTo(1f, tween(160, easing = EASE_SMOOTH)) }
        }
    }
    val line = box.mixSrgb(MillidaPixel.white, 0.35f)
    Canvas(modifier) {
        val w = size.width * 0.8f
        val h = size.height * 0.66f
        val unit = min(w, h) / 100f
        val ox = size.width * 0.1f + (w - unit * 100f) / 2f
        val oy = size.height * 0.14f + (h - unit * 100f) / 2f
        groups.forEachIndexed { gi, group ->
            val k = grows[gi].value
            if (k <= 0f) return@forEachIndexed
            for (pts in group) {
                val path = androidx.compose.ui.graphics.Path()
                var total = 0f
                for (i in 2 until pts.size step 2) total += Offset(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]).getDistance()
                var left = total * k
                path.moveTo(ox + pts[0] * unit, oy + pts[1] * unit)
                for (i in 2 until pts.size step 2) {
                    if (left <= 0f) break
                    val a = Offset(pts[i - 2], pts[i - 1])
                    val b = Offset(pts[i], pts[i + 1])
                    val seg = (b - a).getDistance()
                    val t = min(1f, left / seg)
                    val p = a + (b - a) * t
                    path.lineTo(ox + p.x * unit, oy + p.y * unit)
                    left -= seg
                }
                drawPath(path, box.copy(alpha = 0.55f), style = Stroke(width = 1.6f * unit + 4f * density, cap = StrokeCap.Square, join = StrokeJoin.Miter))
                drawPath(path, line, style = Stroke(width = 1.6f * unit, cap = StrokeCap.Square, join = StrokeJoin.Miter))
            }
        }
    }
}

@Composable
private fun Pips(need: Int, hits: Int, waiting: Boolean, tone: Color, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val blink: State<Float>? = if (waiting && !reduced) {
        rememberInfiniteTransition(label = "pips").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "pips-phase")
    } else {
        null
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until need) {
            val on = i < hits
            Box(
                Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        val b = blink?.value
                        alpha = if (b == null) 1f else {
                            val local = ((b - min(i, 4) * 0.15f / 0.9f) % 1f + 1f) % 1f
                            if (local < 0.5f) 1f else 0.25f
                        }
                    }
                    .pixelSurface(if (on) tone else MillidaPixel.white.copy(alpha = 0.2f), MillidaCut.badge),
            )
        }
    }
}

@Composable
private fun TapWord(text: String, size: Float, modifier: Modifier, faint: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val pulse: State<Float>? = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "tap").animateFloat(0f, 1f, infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "tap-phase")
    }
    Text(
        text,
        modifier = modifier.graphicsLayer {
            val s = 1f + 0.12f * (pulse?.value ?: 0f)
            scaleX = s
            scaleY = s
            alpha = if (faint) 0.7f else 1f
        },
        style = heavy(size).copy(letterSpacing = (-0.02).em, shadow = Shadow(Color.Black, Offset(0f, 4f * LocalDensity.current.density), 0f)),
        color = MillidaPixel.white,
        maxLines = 1,
    )
}

@Composable
private fun LevelPlate(level: Rarity, up: Boolean, plus: Boolean, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val anim = remember { Animatable(if (up && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) { if (anim.value < 1f) anim.animateTo(1f, tween(460, easing = LinearEasing)) }
    val ink = if (level == Rarity.RELIC) level.color else MillidaPixel.revealInk
    val fill = if (level == Rarity.RELIC) Rarity.relicBackground else level.color
    Text(
        (level.title + if (plus) "+" else "").uppercase(),
        modifier = modifier
            .graphicsLayer {
                val p = anim.value
                val s = keyframes(p, EASE_SLAM, 0f, 2f, 0.6f, 0.92f, 1f, 1f)
                scaleX = s
                scaleY = s
                alpha = keyframes(p, null, 0f, 0f, 0.6f, 1f, 1f, 1f)
            }
            .pixelSurface(fill, MillidaCut.control)
            .drawBehind { drawRect(MillidaPixel.revealShade, Offset(0f, size.height - 5.dp.toPx()), Size(size.width, 5.dp.toPx())) }
            .padding(horizontal = 14.dp, vertical = 4.dp),
        style = heavy(if (up) 30f else 22f).copy(letterSpacing = 0.1.em),
        color = ink,
        maxLines = 1,
    )
}

@Composable
private fun LeftPlate(left: Int, box: Color, modifier: Modifier) {
    Box(
        modifier
            .heightIn(min = 96.dp)
            .widthIn(min = 96.dp)
            .pixelSurface(box, MillidaCut.plate)
            .drawBehind { drawRect(MillidaPixel.revealShade, Offset(0f, size.height - 10.dp.toPx()), Size(size.width, 10.dp.toPx())) }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(left.toString(), style = heavy(64f, 64f), color = MillidaPixel.revealInk, maxLines = 1)
    }
}

@Composable
private fun Stalled(onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 420.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("Сервер не ответил"), style = heavy(22f), color = MillidaPixel.white, textAlign = TextAlign.Center)
        Text(
            tr("Награда не пропадёт: повтори или закрой окно"),
            style = heavy(14f).copy(fontWeight = FontWeight.Medium),
            color = MillidaPixel.whiteSoft,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MPrimaryButton(tr("Повторить"), onClick = onRetry, size = MButtonSize.Small)
            MSecondaryButton(tr("Закрыть"), onClick = onClose, size = MButtonSize.Small)
        }
    }
}

/** One reward out of the chest: it flies up from the chest, lands, and the next tap brings the next. */
@Composable
private fun OneReward(card: RevealCard, areaH: Dp, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val rank = card.revealRank()
    val tone = card.revealTone()
    val fly = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) fly.animateTo(1f, tween(500, easing = LinearEasing)) }
    val density = LocalDensity.current
    Box(modifier) {
        if (rank >= Rarity.EPIC.ordinal) RevealConfetti(Modifier.fillMaxSize(), tone = tone)
        RevealFlash(tone, Modifier.fillMaxSize())
        RevealBurst(18 + rank * 6, 240f + rank * 30f, 7 + rank, tone, Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            FitToBox(
                Modifier
                    .weight(1f, fill = false)
                    .graphicsLayer {
                        val p = fly.value
                        val start = areaH.toPx() * 0.34f
                        translationY = keyframes(p, EASE_SLAM, 0f, start, 0.55f, -18f * density.density, 1f, 0f)
                        val s = keyframes(p, EASE_SLAM, 0f, 0.15f, 0.55f, 1.08f, 1f, 1f)
                        scaleX = s
                        scaleY = s
                        alpha = keyframes(p, null, 0f, 0f, 0.55f, 1f, 1f, 1f)
                    },
            ) {
                BigReward(card)
            }
            TapWord(tr("Жми"), 22f, Modifier, faint = true)
        }
    }
}

/** The reward on the stage: an item is its rarity word and a big card, currency a big sum, fragments a sum with the item they build. */
@Composable
private fun BigReward(card: RevealCard) {
    val tone = card.revealTone()
    val item = card.item
    val glowShadow = Shadow(Color.Black, Offset(0f, 6f * LocalDensity.current.density), 0f)
    if (item != null && card.kind == RewardKind.FRAGMENTS) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CurrencyArt(fragmentArt(card.rarityOf()), 88.dp)
                Text("+" + groupDigits(card.amount), style = heavy(96f, 96f).copy(letterSpacing = (-0.03).em, shadow = glowShadow), color = MillidaPixel.white, maxLines = 1)
            }
            Text(fragmentWord(card.amount).uppercase(), style = heavy(22f).copy(letterSpacing = 0.1.em), color = tone, maxLines = 1)
            Row(
                Modifier
                    .padding(top = 14.dp)
                    .widthIn(max = 360.dp)
                    .pixelSurface(tone.mixSrgb(MillidaPixel.revealPlate, 0.26f), MillidaCut.plate)
                    .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(84.dp).pixelSurface(MillidaPixel.revealShade, MillidaCut.control), contentAlignment = Alignment.Center) {
                    ItemArt(item, side = 72.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = heavy(17f, 20f), color = MillidaPixel.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    card.rarityOf()?.let { MRarityPlate(it) }
                    if (card.need > 0) FragBar(card.have, card.need, card.rarityOf(), card.gain, Modifier.width(220.dp))
                }
            }
        }
        return
    }
    if (item != null && !card.isCurrency()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                (card.rarityOf()?.title ?: "").uppercase(),
                style = heavy(34f).copy(letterSpacing = 0.1.em, shadow = Shadow(Color.Black, Offset(0f, 2f * LocalDensity.current.density), 0f)),
                color = tone,
                maxLines = 1,
            )
            RewardCardView(card, big = true)
        }
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            CurrencyArt(if (card.kind == RewardKind.RUBIES) "ruby" else "shard", 150.dp)
        }
        Text("+" + groupDigits(card.cashAmount()), style = heavy(96f, 96f).copy(letterSpacing = (-0.03).em, shadow = glowShadow), color = MillidaPixel.white, maxLines = 1)
        Text(card.cashWord().uppercase(), style = heavy(22f).copy(letterSpacing = 0.1.em), color = tone, maxLines = 1)
    }
}

/** A reward card: the picture on its plate, the rarity, the name or the sum (CaseReveal.tsx RewardCard). */
@Composable
internal fun RewardCardView(card: RevealCard, big: Boolean = false, modifier: Modifier = Modifier) {
    val tone = card.revealTone()
    val item = card.item?.takeIf { !card.isCurrency() }
    val colors = millida
    Column(
        modifier
            .width(if (big) 320.dp else 150.dp)
            .pixelSurface(tone.mixSrgb(MillidaPixel.revealPlate, 0.3f), MillidaCut.plate)
            .drawBehind { drawRect(tone, Offset(0f, size.height - 6.dp.toPx()), Size(size.width, 6.dp.toPx())) }
            .padding(start = if (big) 16.dp else 10.dp, end = if (big) 16.dp else 10.dp, top = if (big) 16.dp else 10.dp, bottom = if (big) 22.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pixelSurface(MillidaPixel.revealShade, MillidaCut.control),
            contentAlignment = Alignment.Center,
        ) {
            val pic = maxWidth * 0.86f
            when {
                item != null -> ItemArt(item, side = pic)
                card.kind == RewardKind.RUBIES -> CurrencyArt("ruby", if (big) 120.dp else 64.dp)
                else -> CurrencyArt("shard", if (big) 120.dp else 64.dp)
            }
            if (item != null && !big) card.rarityOf()?.let { MRarityPlate(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
        }
        Text(
            if (item != null) item.name else "+" + groupDigits(card.cashAmount()) + " " + card.cashWord(),
            style = heavy(if (big) 22f else 14f, if (big) 26f else 17f),
            color = MillidaPixel.white,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item != null && card.kind == RewardKind.FRAGMENTS && card.amount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CurrencyArt(fragmentArt(card.rarityOf()), 14.dp)
                Text("+" + groupDigits(card.amount) + " " + fragmentWord(card.amount), style = heavy(13f).copy(fontWeight = FontWeight.ExtraBold), color = tone, maxLines = 1)
            }
        }
        if (item != null && card.kind == RewardKind.FRAGMENTS && card.need > 0) FragBar(card.have, card.need, card.rarityOf(), card.gain, Modifier.fillMaxWidth())
        if (card.duplicate) Text(tr("повтор"), style = heavy(13f).copy(fontWeight = FontWeight.ExtraBold), color = colors.fgSubtle)
    }
}

/** Fragments of an item: the icon, «7/20», a bar with ticks; what came now is brighter; done — in accent (rarityUi.tsx FragBar). */
@Composable
internal fun FragBar(have: Int, need: Int, rarity: Rarity?, gain: Int, modifier: Modifier = Modifier) {
    val colors = millida
    val done = have >= need
    val tone = if (done) colors.accent else rarity?.color ?: Rarity.RARE.color
    val pct = if (need > 0) min(1f, have.toFloat() / need) else 0f
    val ticks = if (need > 0) min(9, max(1, Math.round(need / 5f) - 1)) else 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        CurrencyArt(fragmentArt(rarity), 14.dp)
        Text(
            buildAnnotatedFrag(have, need),
            style = heavy(11f, 13f),
            color = if (done) colors.accent else colors.fg,
            maxLines = 1,
        )
        Box(
            Modifier
                .weight(1f)
                .widthIn(min = 24.dp)
                .height(8.dp)
                .pixelSurface(colors.inset, 3.dp)
                .drawBehind {
                    drawRect(tone, size = Size(size.width * pct, size.height))
                    if (gain > 0 && !done && need > 0) {
                        val g = gain.toFloat() / need
                        drawRect(MillidaPixel.white.copy(alpha = 0.55f), Offset(size.width * max(0f, pct - g), 0f), Size(size.width * g, size.height))
                    }
                    for (k in 0 until ticks) {
                        val x = size.width * (k + 1) / (ticks + 1)
                        drawRect(Color.Black.copy(alpha = 0.45f), Offset(x, 0f), Size(2.dp.toPx(), size.height))
                    }
                },
        )
    }
}

@Composable
private fun buildAnnotatedFrag(have: Int, need: Int): androidx.compose.ui.text.AnnotatedString {
    val faint = millida.fgFaint
    return androidx.compose.ui.text.buildAnnotatedString {
        append(have.toString())
        pushStyle(androidx.compose.ui.text.SpanStyle(color = faint, fontWeight = FontWeight.ExtraBold))
        append("/$need")
        pop()
    }
}

/** All the rewards in a grid: «Надеть» if something new to wear came, then «Ещё раз» or «Готово». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(
    title: String,
    rewards: List<RevealCard>,
    extra: (@Composable (RevealCard, Int) -> Unit)?,
    again: RevealAgain?,
    onClose: () -> Unit,
    onWear: () -> Unit,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val top = rewards.maxOfOrNull { it.revealRank() } ?: 0
    val items = rewards.any { it.item != null && it.kind == RewardKind.ITEM }
    Box(modifier.clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        if (top >= Rarity.EPIC.ordinal) RevealConfetti(Modifier.fillMaxSize())
        FitToBox(Modifier.align(Alignment.Center).padding(bottom = 48.dp)) {
            Column(
                Modifier.widthIn(max = 980.dp).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(title, style = heavy(26f), color = MillidaPixel.white, textAlign = TextAlign.Center)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rewards.forEachIndexed { i, card ->
                        key(i) {
                            val enter = remember { Animatable(if (reduced) 1f else 0f) }
                            LaunchedEffect(Unit) { if (!reduced) enter.animateTo(1f, tween(400, delayMillis = i * 90, easing = LinearEasing)) }
                            Column(
                                Modifier.graphicsLayer {
                                    val p = enter.value
                                    val s = keyframes(p, EASE_SLAM, 0f, 1.8f, 0.6f, 0.94f, 1f, 1f)
                                    scaleX = s
                                    scaleY = s
                                    translationY = keyframes(p, EASE_SLAM, 0f, -30f * density, 0.6f, 0f, 1f, 0f)
                                    alpha = keyframes(p, null, 0f, 0f, 0.6f, 1f, 1f, 1f)
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RewardCardView(card)
                                extra?.invoke(card, i)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (items) MPrimaryButton(tr("Надеть"), onClick = onWear, size = MButtonSize.Large, modifier = Modifier.widthIn(min = 180.dp))
                    if (again != null) {
                        AgainButton(again, Modifier.widthIn(min = 180.dp))
                    } else {
                        MSecondaryButton(tr("Готово"), onClick = onClose, size = MButtonSize.Large, modifier = Modifier.widthIn(min = 180.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgainButton(again: RevealAgain, modifier: Modifier = Modifier) {
    if (again.primary) {
        MPrimaryButton(again.label, onClick = again.onClick, icon = again.icon, size = MButtonSize.Large, modifier = modifier)
    } else {
        MSecondaryButton(again.label, onClick = again.onClick, icon = again.icon, size = MButtonSize.Large, modifier = modifier)
    }
}

/** ×10: ten cards face down, each tap flips the next, the best one last with fanfare; flipped all — the summary. */
@Composable
private fun Deal(
    opens: List<RevealOpen>,
    order: List<Int>,
    flipped: Int,
    done: Boolean,
    title: String,
    tier: String,
    tint: Int?,
    box: Color,
    again: RevealAgain?,
    onClose: () -> Unit,
    onWear: () -> Unit,
    areaH: Dp,
    modifier: Modifier,
) {
    val bestRank = opens.getOrNull(order.lastOrNull() ?: -1)?.let { if (it.kind == OPEN_ITEM) (it.rarity ?: it.main()?.rarityOf())?.ordinal ?: -1 else -1 } ?: -1
    var rubies = 0L
    var shards = 0L
    for (o in opens) for (r in o.cards) {
        if (r.kind == RewardKind.RUBIES) rubies += r.amount else if (r.kind == RewardKind.SHARDS) shards += r.amount
    }
    BoxWithConstraints(modifier) {
        if (done && bestRank >= Rarity.EPIC.ordinal) RevealConfetti(Modifier.fillMaxSize(), n = 70)
        val wide = maxWidth
        val narrow = wide < 720.dp
        FitToBox(Modifier.align(Alignment.Center).padding(bottom = 48.dp)) {
            Column(
                Modifier.width(if (narrow) wide else 900.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (done) Text(title + " ×" + opens.size, style = heavy(26f), color = MillidaPixel.white, textAlign = TextAlign.Center)
                val columns = 5
                val gap = if (narrow) 8.dp else 12.dp
                val cell = if (narrow) (wide - gap * (columns - 1)) / columns else 150.dp
                order.chunked(columns).forEachIndexed { row, chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        chunk.forEachIndexed { col, oi ->
                            val at = row * columns + col
                            key(oi) {
                                DealCell(
                                    open = opens[oi],
                                    at = at,
                                    on = at < flipped,
                                    best = at == order.size - 1,
                                    bestRank = bestRank,
                                    done = done,
                                    tier = tier,
                                    tint = tint,
                                    box = box,
                                    cell = cell,
                                    narrow = narrow,
                                    areaH = areaH,
                                    onWear = onWear,
                                )
                            }
                        }
                    }
                }
                if (done) {
                    if (rubies > 0 || shards > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (rubies > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CurrencyArt("ruby", 20.dp)
                                Text("+" + groupDigits(rubies), style = heavy(20f), color = MillidaPixel.white)
                            }
                            if (shards > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CurrencyArt("shard", 20.dp)
                                Text("+" + groupDigits(shards), style = heavy(20f), color = MillidaPixel.white)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (again != null) AgainButton(again, Modifier.widthIn(min = 180.dp))
                        MSecondaryButton(tr("Готово"), onClick = onClose, size = MButtonSize.Large, modifier = Modifier.widthIn(min = 180.dp))
                    }
                } else {
                    TapWord(tr("Жми"), 22f, Modifier, faint = true)
                }
            }
        }
    }
}

@Composable
private fun DealCell(
    open: RevealOpen,
    at: Int,
    on: Boolean,
    best: Boolean,
    bestRank: Int,
    done: Boolean,
    tier: String,
    tint: Int?,
    box: Color,
    cell: Dp,
    narrow: Boolean,
    areaH: Dp,
    onWear: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val card = open.main() ?: return
    val tone = card.revealTone()
    val rarity = open.rarity ?: card.rarityOf()
    val hot = open.kind == OPEN_ITEM && (rarity?.ordinal ?: -1) >= Rarity.EPIC.ordinal
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) enter.animateTo(1f, tween(460, delayMillis = at * 60, easing = EASE_SMOOTH)) }
    val flip = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) { flip.animateTo(if (on) 1f else 0f, tween(if (reduced) 0 else 420, easing = EASE_FLIP)) }
    val grow = remember { Animatable(if (best && on) 1f else 0f) }
    LaunchedEffect(best && on) { if (best && on && grow.value < 1f && !reduced) grow.animateTo(1f, tween(600, easing = LinearEasing)) else if (best && on) grow.snapTo(1f) }
    val pulse: State<Float>? = if (hot && !on && !reduced) {
        rememberInfiniteTransition(label = "hot").animateFloat(0.25f, 0.05f, infiniteRepeatable(tween(1200, easing = EASE_SMOOTH), RepeatMode.Reverse), label = "hot-phase")
    } else {
        null
    }
    val density = LocalDensity.current
    Column(
        Modifier
            .width(cell)
            .graphicsLayer {
                val p = enter.value
                translationY = (1f - p) * areaH.toPx() * 0.3f
                val s = 0.2f + 0.8f * p
                scaleX = s
                scaleY = s
                alpha = min(1f, p / 0.7f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (best && on && bestRank >= Rarity.RARE.ordinal) {
                RevealRays(tone, Modifier.requiredSize(1240.dp))
                RevealBurst(40, 300f, 41, tone, Modifier.requiredSize(700.dp))
            }
            Box(
                Modifier.graphicsLayer {
                    val g = grow.value
                    val s = if (best && on) keyframes(g, EASE_SLAM, 0f, 1f, 0.45f, 1.22f, 1f, 1.08f) else 1f
                    scaleX = s
                    scaleY = s
                },
            ) {
                val angle = flip.value * 180f
                Box(
                    Modifier
                        .graphicsLayer {
                            rotationY = angle - 180f
                            cameraDistance = 12f * density.density
                            alpha = if (angle >= 90f) 1f else 0f
                        }
                        .drawWithContent {
                            drawContent()
                            if (best && on) drawRect(tone, style = Stroke(width = 8.dp.toPx()))
                        },
                ) {
                    RewardCardView(card, modifier = Modifier.width(cell))
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            rotationY = angle
                            cameraDistance = 12f * density.density
                            alpha = if (angle < 90f) 1f else 0f
                        }
                        .pixelSurface(if (hot && !on) tone.mixSrgb(MillidaPixel.revealPlate, 0.45f) else box.mixSrgb(MillidaPixel.revealPlate, 0.55f), MillidaCut.plate)
                        .drawBehind {
                            drawRect(MillidaPixel.revealShade, Offset(0f, size.height - 8.dp.toPx()), Size(size.width, 8.dp.toPx()))
                            val p = pulse?.value
                            if (p != null) drawRect(tone.copy(alpha = p))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (!done) {
                        Chest3D(
                            tier = tier,
                            mode = if (hot) ChestMode.Ready else ChestMode.Closed,
                            tint = tint,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                }
            }
        }
        if (done && card.item != null && card.kind == RewardKind.ITEM) {
            MSecondaryButton(tr("Надеть"), onClick = onWear, size = MButtonSize.Small, modifier = Modifier.fillMaxWidth())
        }
    }
}
