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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.millida.launcher.ChestOpening
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.RevealCard
import net.millida.launcher.RewardKind
import net.millida.launcher.RewardReveal
import net.millida.launcher.XrayRun
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.rubies.ChestOdds
import net.millida.launcher.core.rubies.PendingChest
import net.millida.launcher.core.rubies.PityLeft
import net.millida.launcher.ui.DropWindowDim
import net.millida.launcher.ui.ExtendWindowUnderCutout
import net.millida.launcher.ui.rememberWindowOverflow
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPixelLoader
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
import net.millida.launcher.ui.UiFeedback
import net.millida.launcher.ui.drawRays
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.steppedPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val SHAKE_MIN_MS = 1500L
private const val SHAKE_RAMP_MS = 1300f
private const val HIT_EVERY_MS = 300L
private const val MAX_HITS = 4
private const val LID_MS = 420f
private const val FLASH_MS = 500f
private const val JUMP_MS = 300f
private const val SQUASH_MS = 120f
private const val UNSQUASH_MS = 180f
private const val RAYS_MS = 500f
private const val PILLAR_MS = 350f
private const val FIRST_CARD_MS = 260L
private const val BEAT_MS = 460L
private const val PLOP_MS = 520
private const val GLOW_MS = 1100
private const val CARD_FLASH_MS = 500
private const val FRAGMENT_CELLS = 10
private const val FRAGMENT_STEP_MS = 160
private const val FRAGMENT_FILL_MS = 360
private const val RAY_SPIN_PER_S = 0.25f
private const val IDLE_ASK_MS = 2600L
private const val BURST_SPARKS = 40
private const val TRICKLE_PER_S = 10f
private const val DETACH_AFTER_MS = 8_000L

private val CARD_WIDTH = 136.dp

private const val VOXEL_PER_CELL = 0.8f
private const val VOXEL_SEAM = 10f

private enum class Phase { Idle, Shaking, Opened, Done, Failed }

private class Spark(
    val born: Long,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val color: Color,
    val life: Long,
)

/** Frame-loop state; composition reads only phase and shown, so the page does not recompose every frame. */
@Stable
private class ChestSceneState(val reduced: Boolean, seed: Int) {
    var clock by mutableLongStateOf(0L)
    var bornAt = 0L
    var phase by mutableStateOf(Phase.Idle)
    var shown by mutableIntStateOf(0)
    var shakeAt = 0L
    var openAt = 0L
    var hits = 0
    var sparkDebt = 0f
    var lastFrame = 0L
    val sparks = ArrayList<Spark>()
    val random = Random(seed)
}

/** The lid waits at least 1.5 s even when the answer is instant. */
@Composable
internal fun ChestScene(
    opening: ChestOpening,
    odds: ChestOdds?,
    oddsFailed: Boolean,
    pityAfter: Int,
    pityLegendAfter: Int,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onWardrobe: () -> Unit,
    onRetryOdds: () -> Unit,
    topUp: ChestTopUp? = null,
) {
    key(opening.serial) {
        ChestSceneBody(opening, odds, oddsFailed, pityAfter, pityLegendAfter, onStart, onNext, onClose, onWardrobe, onRetryOdds, topUp)
    }
}

/** "Докупить" under a fragments card of the summary: the started item is finished only by buying the rest. */
internal class ChestTopUp(
    val done: Set<String>,
    val busy: (String) -> Boolean,
    val onTopUp: (RevealCard) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChestSceneBody(
    opening: ChestOpening,
    odds: ChestOdds?,
    oddsFailed: Boolean,
    pityAfter: Int,
    pityLegendAfter: Int,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onWardrobe: () -> Unit,
    onRetryOdds: () -> Unit,
    topUp: ChestTopUp?,
) {
    val reduced = LocalReducedMotion.current
    val feedback = LocalUiFeedback.current
    val stage = remember { ChestSceneState(reduced, opening.serial.toInt()) }
    val latest by rememberUpdatedState(opening)
    val cards = remember(opening.result) { opening.cards }
    val latestCards by rememberUpdatedState(cards)
    val tier = opening.chest.tier

    LaunchedEffect(stage) {
        while (true) {
            val now = withFrameMillis { it }
            if (stage.bornAt == 0L) stage.bornAt = now
            stage.clock = now
            stage.step(now, latest, latestCards, feedback, tier)
        }
    }

    val start: () -> Unit = {
        if (stage.phase == Phase.Idle) {
            stage.phase = Phase.Shaking
            stage.shakeAt = stage.clock
            stage.hits = 0
            feedback.chestHit(0)
            onStart()
        }
    }
    val skip: () -> Unit = {
        if (stage.phase == Phase.Opened && stage.shown < latestCards.size) {
            stage.shown = latestCards.size
            feedback.chestCard(bestRank(latestCards))
        }
    }
    var detachable by remember { mutableStateOf(false) }
    LaunchedEffect(stage.phase) {
        detachable = false
        if (stage.phase == Phase.Shaking) {
            delay(DETACH_AFTER_MS)
            detachable = true
        }
    }
    val back: () -> Unit = {
        when (stage.phase) {
            Phase.Idle, Phase.Done, Phase.Failed -> onClose()
            Phase.Opened -> skip()
            Phase.Shaking -> if (detachable) onClose() else Unit
        }
    }

    SceneWindow(onBack = back) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .clickable(remember { MutableInteractionSource() }, indication = null) { skip() },
        ) {
            val closable = stage.phase == Phase.Idle ||
                stage.phase == Phase.Done ||
                stage.phase == Phase.Failed ||
                (stage.phase == Phase.Shaking && detachable)
            SceneHeader(
                kicker = if (opening.left > 0) "Ещё ${opening.left}" else "Сундук",
                title = chestName(tier) + " сундук",
                tone = chestTone(tier),
                onClose = if (closable) onClose else null,
            )
            val stageHeight = (LocalConfiguration.current.screenHeightDp * 0.36f).dp.coerceIn(200.dp, 320.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stageHeight)
                    .clickable(
                        remember { MutableInteractionSource() },
                        indication = null,
                        enabled = stage.phase == Phase.Idle,
                        role = Role.Button,
                        onClickLabel = "Открыть сундук",
                    ) { start() },
            ) {
                ChestStage(stage, tier, latestCards, Modifier.fillMaxSize())
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (stage.phase) {
                    Phase.Idle -> {
                        OddsPanel(tier, odds, pityAfter, pityLegendAfter, oddsFailed, onRetryOdds, Modifier.fillMaxWidth())
                        MPrimaryButton(
                            text = "Открыть",
                            onClick = start,
                            modifier = Modifier.fillMaxWidth(),
                            icon = MIcons.chest,
                            size = MButtonSize.Large,
                        )
                    }
                    Phase.Shaking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MPixelLoader(color = MillidaPixel.white)
                            Spacer(Modifier.width(10.dp))
                            Text("Открываем…", style = MillidaType.rowTitle, color = MillidaPixel.whiteSoft)
                        }
                        if (detachable) {
                            Text(
                                "Отвечает долго — закрой, награда придёт сама",
                                style = MillidaType.small,
                                color = MillidaPixel.whiteSoft,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Phase.Failed -> {
                        Text(
                            opening.error ?: "Сундук не открылся — попробуй ещё раз",
                            style = MillidaType.body,
                            color = MillidaPixel.white,
                            textAlign = TextAlign.Center,
                        )
                        MSecondaryButton("Закрыть", onClick = onClose)
                    }
                    Phase.Opened, Phase.Done -> {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (index in 0 until min(stage.shown, cards.size)) {
                                key(index) {
                                    val card = cards[index]
                                    val item = card.item
                                    if (stage.phase == Phase.Done && topUp != null && card.topUp > 0 && item != null) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            RevealCardView(card)
                                            if (item.code in topUp.done) {
                                                MBadge("Твоя", color = MillidaPixel.white, background = MillidaPixel.glintSoft, icon = MIcons.check)
                                            } else {
                                                MSecondaryButton(
                                                    "Докупить ${groupDigits(card.topUp)}",
                                                    onClick = { topUp.onTopUp(card) },
                                                    icon = MIcons.ruby,
                                                    size = MButtonSize.Small,
                                                    loading = topUp.busy("fragments:" + item.code),
                                                )
                                            }
                                        }
                                    } else {
                                        RevealCardView(card)
                                    }
                                }
                            }
                        }
                        if (stage.phase == Phase.Done) {
                            ChestSummary(opening, onNext, onClose, onWardrobe)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChestSummary(opening: ChestOpening, onNext: () -> Unit, onClose: () -> Unit, onWardrobe: () -> Unit) {
    val pity: PityLeft? = opening.result?.pityLeft
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pity != null && (pity.epic > 0 || pity.legend > 0)) {
            Text(
                "До эпической ${pity.epic} · до легендарной ${pity.legend}",
                style = MillidaType.small,
                color = MillidaPixel.whiteSoft,
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (opening.fresh) MSecondaryButton("В гардероб", onClick = onWardrobe, icon = MIcons.shirt)
            if (opening.left > 0) {
                MPrimaryButton("Следующий", onClick = onNext, size = MButtonSize.Large)
            } else {
                MPrimaryButton("Готово", onClick = onClose, size = MButtonSize.Large)
            }
        }
    }
}

private fun cardRank(card: RevealCard): Int = when (card.kind) {
    RewardKind.ITEM, RewardKind.FRAGMENTS -> rarityIndex(Rarity.of(card.rarity) ?: Rarity.of(card.item?.rarity))
    RewardKind.CHEST -> rarityIndex(chestRarity(card.tier))
    else -> 1
}

private fun bestRank(cards: List<RevealCard>): Int = cards.maxOfOrNull(::cardRank) ?: 1

private fun ChestSceneState.step(now: Long, opening: ChestOpening, cards: List<RevealCard>, feedback: UiFeedback, tier: String) {
    val dt = if (lastFrame == 0L) 0L else min(100L, now - lastFrame)
    lastFrame = now
    when (phase) {
        Phase.Idle, Phase.Failed -> Unit
        Phase.Shaking -> {
            val elapsed = now - shakeAt
            if (!reduced) {
                val due = min(MAX_HITS, (elapsed / HIT_EVERY_MS).toInt())
                while (hits < due) {
                    hits += 1
                    feedback.chestHit(hits)
                }
                val k = min(1f, elapsed / SHAKE_RAMP_MS)
                spawn(now, (4f + 18f * k) * dt / 1000f, tier, burst = false)
            }
            when {
                opening.error != null -> {
                    phase = Phase.Failed
                    feedback.play(UiSoundEvent.Error)
                }
                opening.result != null && (reduced || elapsed >= SHAKE_MIN_MS) -> {
                    phase = Phase.Opened
                    openAt = now
                    feedback.chestBurst()
                    if (!reduced) spawn(now, BURST_SPARKS.toFloat(), tier, burst = true)
                }
            }
        }
        Phase.Opened -> {
            val elapsed = now - openAt
            val first = LID_MS.toLong() + FIRST_CARD_MS
            val due = when {
                reduced -> cards.size
                elapsed < first -> 0
                else -> min(cards.size, 1 + ((elapsed - first) / BEAT_MS).toInt())
            }
            if (due > shown) {
                if (reduced || due - shown > 1) {
                    feedback.chestCard(bestRank(cards.subList(shown, due)))
                } else {
                    feedback.chestCard(cardRank(cards[shown]))
                }
                shown = due
            }
            if (!reduced) spawn(now, TRICKLE_PER_S * dt / 1000f, tier, burst = false)
            if (shown >= cards.size && (reduced || elapsed >= first + BEAT_MS * max(0, cards.size - 1) + PLOP_MS)) {
                phase = Phase.Done
            }
        }
        Phase.Done -> if (!reduced) spawn(now, TRICKLE_PER_S * dt / 1000f, tier, burst = false)
    }
    sparks.removeAll { now - it.born > it.life }
}

private fun ChestSceneState.spawn(now: Long, amount: Float, tier: String, burst: Boolean) {
    sparkDebt += amount
    val palette = chestPalette(tier)
    while (sparkDebt >= 1f) {
        sparkDebt -= 1f
        val angle = if (burst) random.nextFloat() * 2f * PI.toFloat() else (-PI / 2).toFloat() + (random.nextFloat() - 0.5f) * 1.6f
        val speed = if (burst) 10f + random.nextFloat() * 26f else 6f + random.nextFloat() * 12f
        sparks += Spark(
            born = now,
            x = (random.nextFloat() - 0.5f) * 12f,
            y = 0f,
            vx = cos(angle) * speed,
            vy = sin(angle) * speed - if (burst) 8f else 4f,
            size = if (random.nextFloat() < 0.3f) 1f else 0.6f,
            color = if (random.nextBoolean()) palette.sparkColor else palette.glowColor,
            life = 700L + random.nextInt(500),
        )
    }
}

@Composable
private fun ChestStage(stage: ChestSceneState, tier: String, cards: List<RevealCard>, modifier: Modifier) {
    val model = remember(tier) { ChestModel(tier) }
    val pose = remember { ChestPose() }
    val baseTone = chestTone(tier)
    Canvas(modifier) {
        val now = stage.clock
        val phase = stage.phase
        val cell = max(2f, floor(min(size.width * 0.46f / CHEST_COLUMNS, size.height * 0.56f / CHEST_ROWS)))
        val chestHeight = cell * CHEST_ROWS
        val baseTop = floor(size.height * 0.62f - chestHeight / 2f)
        val unit = cell * VOXEL_PER_CELL
        val floorY = baseTop + chestHeight
        val seam = Offset(size.width / 2f, floorY - unit * VOXEL_SEAM)
        val shownTone = cards.getOrNull(stage.shown - 1)?.tone() ?: baseTone
        val tone = if (phase == Phase.Opened || phase == Phase.Done) shownTone else baseTone
        val sinceOpen = (now - stage.openAt).toFloat()
        val opened = phase == Phase.Opened || phase == Phase.Done

        if (opened) {
            val growth = if (stage.reduced) 1f else min(1f, sinceOpen / RAYS_MS)
            val spin = if (stage.reduced) 0f else sinceOpen / 1000f * RAY_SPIN_PER_S
            drawRays(seam, max(size.width, size.height) * 0.75f * growth, tone.copy(alpha = 0.26f), rotation = spin)
            val pillar = if (stage.reduced) 1f else min(1f, sinceOpen / PILLAR_MS)
            val pillarHeight = seam.y * pillar
            drawRect(tone.copy(alpha = 0.16f), Offset(seam.x - cell * 4f, seam.y - pillarHeight), Size(cell * 8f, pillarHeight))
            drawRect(tone.copy(alpha = 0.26f), Offset(seam.x - cell * 2f, seam.y - pillarHeight), Size(cell * 4f, pillarHeight))
        } else if (phase == Phase.Shaking && !stage.reduced) {
            val k = min(1f, (now - stage.shakeAt) / SHAKE_RAMP_MS)
            drawRays(seam, max(size.width, size.height) * 0.5f, tone.copy(alpha = 0.06f + 0.1f * k))
        }

        var dx = 0f
        var dy = 0f
        var degrees = 0f
        var scaleX = 1f
        var scaleY = 1f
        val seconds = (now - stage.bornAt) / 1000f
        pose.yaw = REST_YAW
        pose.lid = if (opened) LID_OPEN else 0f
        pose.lit = opened
        when {
            stage.reduced -> Unit
            phase == Phase.Idle -> {
                dy = -abs(sin(2.4f * seconds)) * 1.4f * cell
                degrees = sin(1.3f * seconds) * 2.2f
                pose.yaw = REST_YAW + sin(0.9f * seconds) * 0.12f
                val ask = ((now - stage.bornAt) % IDLE_ASK_MS).toFloat() / IDLE_ASK_MS
                if (ask > 0.82f) pose.lid = -sin(PI.toFloat() * (ask - 0.82f) / 0.18f) * 0.22f
            }
            phase == Phase.Shaking -> {
                val k = min(1f, (now - stage.shakeAt) / SHAKE_RAMP_MS)
                val wave = 2f * PI.toFloat() * (30f + 26f * k) * seconds
                degrees = (0.04f + 0.16f * k) * sin(wave) * 28f
                dx = sin(wave * 1.37f) * k * 0.8f * cell
                pose.lid = -abs(sin(wave * 0.5f)) * (0.05f + k * 0.2f)
            }
            opened -> {
                pose.lid = LID_OPEN * chestEaseOutBack(min(1f, sinceOpen / LID_MS))
                pose.yaw = REST_YAW + sin(0.7f * seconds) * 0.08f
                if (sinceOpen < JUMP_MS) dy = -sin(PI.toFloat() * sinceOpen / JUMP_MS) * 3f * cell
                scaleY = when {
                    sinceOpen < SQUASH_MS -> 1f - 0.12f * sinceOpen / SQUASH_MS
                    sinceOpen < SQUASH_MS + UNSQUASH_MS -> 0.88f + 0.12f * (sinceOpen - SQUASH_MS) / UNSQUASH_MS
                    else -> 1f
                }
                scaleX = 1f + (1f - scaleY) * 0.5f
            }
        }

        val pivot = Offset(size.width / 2f, floorY)
        withTransform({
            translate(floor(dx), floor(dy))
            rotate(degrees, pivot)
            scale(scaleX, scaleY, pivot)
        }) {
            drawChest(model, size.width / 2f, floorY, unit, pose)
        }

        for (spark in stage.sparks) {
            val age = (now - spark.born) / 1000f
            val life = spark.life / 1000f
            val fade = 1f - floor(age / life * 3f) / 3f
            if (fade <= 0f) continue
            val px = seam.x + (spark.x + spark.vx * age) * cell
            val py = seam.y + (spark.y + spark.vy * age + 14f * age * age) * cell
            val side = max(2f, floor(spark.size * cell))
            drawRect(spark.color.copy(alpha = fade), Offset(floor(px), floor(py)), Size(side, side))
        }

        if (opened && !stage.reduced && sinceOpen < FLASH_MS) {
            val alpha = 0.85f * (1f - floor(sinceOpen / FLASH_MS * 4f) / 4f)
            drawFlash(seam, cell, tone.mixSrgb(MillidaPixel.white, 0.4f).copy(alpha = alpha))
        }
    }
}

/** Hard arms instead of a blurred glow: the pixel language has no blur. */
private fun DrawScope.drawFlash(center: Offset, cell: Float, color: Color) {
    val core = cell * 8f
    val arm = cell * 26f
    val thick = cell * 3f
    drawRect(color, Offset(center.x - core / 2f, center.y - core / 2f), Size(core, core))
    drawRect(color, Offset(center.x - arm, center.y - thick / 2f), Size(arm * 2f, thick))
    drawRect(color, Offset(center.x - thick / 2f, center.y - arm), Size(thick, arm * 2f))
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

@Composable
private fun SceneHeader(kicker: String, title: String, tone: Color, onClose: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                kicker.uppercase(),
                style = MillidaType.caption,
                color = tone,
                maxLines = 1,
            )
            Text(
                title,
                style = MillidaType.barTitle,
                color = MillidaPixel.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClose != null) {
            MIconButton(MIcons.close, onClick = onClose, label = "Закрыть")
        }
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
        RewardKind.PLUS -> Text("PLUS открыт", style = style, color = colors.fg, textAlign = TextAlign.Center, maxLines = 1)
        RewardKind.STARS -> Text("Путь пройден", style = style, color = colors.fg, textAlign = TextAlign.Center, maxLines = 1)
        RewardKind.CREATOR -> Text(
            "Ты поддерживаешь " + card.item?.name.orEmpty(),
            style = style,
            color = colors.fg,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RewardKind.CHEST -> Text(
            chestName(card.tier) + " сундук",
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
            card.item?.name?.takeIf { it.isNotBlank() } ?: "Вещь",
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
            "${card.amount} ${fragmentWord(card.amount)} → осколки",
            style = MillidaType.small,
            color = colors.fgSubtle,
            textAlign = TextAlign.Center,
        )
        card.kind == RewardKind.FRAGMENTS && card.need > 0 -> {
            FragmentCells(have = card.have, need = card.need, gain = card.gain, completed = card.completed, tone = tone)
            Text(
                if (card.completed) "Собрано!" else "+${card.gain} · ${card.have.coerceAtMost(card.need)}/${card.need}",
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
            "Повтор: +${card.shards} ${shardWord(card.shards)}",
            style = MillidaType.small,
            color = colors.fgSubtle,
            textAlign = TextAlign.Center,
        )
        card.kind == RewardKind.ITEM -> Text("Навсегда", style = MillidaType.small, color = colors.fgSubtle)
        card.kind == RewardKind.PLUS -> Text("Вторая награда дня", style = MillidaType.small, color = colors.fgSubtle, textAlign = TextAlign.Center)
        card.kind == RewardKind.STARS -> Text("Забирай вещь", style = MillidaType.small, color = colors.fgSubtle, textAlign = TextAlign.Center)
        card.kind == RewardKind.CREATOR -> Text(
            "Часть с твоих покупок — автору",
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
                        text = if (reveal.chests.size > 1) "Открыть сундуки (${reveal.chests.size})" else "Открыть сундук",
                        onClick = { onOpenChests(reveal.chests) },
                        size = MButtonSize.Large,
                        icon = MIcons.chest,
                    )
                    MTextButton("Позже", onClick = onDone, color = MillidaPixel.whiteSoft)
                } else {
                    MPrimaryButton("Готово", onClick = onDone, size = MButtonSize.Large)
                }
            }
        }
    }
}

private const val XRAY_SHAKE_MIN_MS = 1500L
private const val XRAY_LIGHT_MS = 380L

/**
 * The paid X-ray case: it shakes until the purchase answers and at least for a
 * moment, then the lid flies and the shared reward moment shows the item.
 */
@Composable
internal fun XrayScene(run: XrayRun, onOpened: (Long) -> Unit) {
    key(run.serial) {
        val feedback = LocalUiFeedback.current
        var minDone by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            feedback.chestHit(0)
            delay(XRAY_SHAKE_MIN_MS)
            minDone = true
        }
        val open = minDone && run.item != null
        LaunchedEffect(open) { if (open) feedback.chestBurst() }
        SceneWindow(onBack = {}) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                SceneHeader(kicker = "Рентген-кейс", title = chestName(run.tier) + " сундук", tone = chestTone(run.tier), onClose = null)
                Chest3D(
                    tier = run.tier,
                    mode = if (open) ChestMode.Open else ChestMode.Shake,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (open) {
                    LaunchedEffect(run.serial) {
                        delay(CHEST_LID_MS.toLong() + XRAY_LIGHT_MS)
                        onOpened(run.serial)
                    }
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
