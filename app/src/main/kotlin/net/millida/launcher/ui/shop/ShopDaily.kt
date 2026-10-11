package net.millida.launcher.ui.shop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.DailyTarget
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.rubies.DailyStatus
import net.millida.launcher.core.rubies.RubyReward
import net.millida.launcher.core.rubies.TrackRow
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.hopAt
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed

private const val READY = "ready"
private const val CLAIMED = "claimed"
private const val LOCKED = "locked"
private const val FUTURE = "future"
private const val WEEK = 7

private val COLUMN_WIDTH = 74.dp
private val HEAD_HEIGHT = 24.dp
private val CELL_HEIGHT = 100.dp
private val ROW_GAP = 6.dp
private val WEEK_GAP = 12.dp
private val BRACKET_WIDTH = 18.dp

internal class PassColumn(
    val day: Int,
    val free: List<RubyReward>,
    val plus: List<RubyReward>,
    val freeState: String,
    val plusState: String,
) {
    val ready: Boolean get() = freeState == READY || plusState == READY
}

/** The service states every cell itself; a cell it left blank is still to come. */
internal fun passColumns(status: DailyStatus): List<PassColumn> = status.track.mapIndexed { index, day ->
    val number = if (day.day > 0) day.day else index + 1
    PassColumn(
        day = number,
        free = day.free,
        plus = day.plus,
        freeState = day.freeState.ifEmpty { FUTURE },
        plusState = day.plusState.ifEmpty { if (status.plus) FUTURE else LOCKED },
    )
}

internal fun readyTargets(columns: List<PassColumn>): List<DailyTarget.Cell> = buildList {
    for (column in columns) {
        if (column.freeState == READY) add(DailyTarget.Cell(column.day, TrackRow.FREE))
        if (column.plusState == READY) add(DailyTarget.Cell(column.day, TrackRow.PLUS))
    }
}

@Composable
internal fun DailyPassCard(
    status: DailyStatus,
    plusPriceKopecks: Long?,
    claiming: Boolean,
    onClaim: (DailyTarget) -> Unit,
    onPlus: () -> Unit,
    onElapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val columns = remember(status) { passColumns(status) }
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Бонус за вход",
                modifier = Modifier.weight(1f),
                style = MillidaType.sectionTitle,
                color = colors.fg,
                maxLines = 1,
            )
            if (status.streak > 0) {
                MBadge("${status.streak} подряд", color = colors.warning, background = colors.warningSoft, icon = MIcons.flame)
            }
        }
        dayMonth(status.season?.endsAt)?.let { end ->
            Spacer(Modifier.height(2.dp))
            Text("Сезон до $end", style = MillidaType.small, color = colors.fgFaint)
        }
        SeasonPass(status, columns, plusPriceKopecks, claiming, onClaim, onPlus, onElapsed)
    }
}

@Composable
private fun SeasonPass(
    status: DailyStatus,
    columns: List<PassColumn>,
    plusPriceKopecks: Long?,
    claiming: Boolean,
    onClaim: (DailyTarget) -> Unit,
    onPlus: () -> Unit,
    onElapsed: () -> Unit,
) {
    val own = status.plus
    if (!own) {
        Spacer(Modifier.height(10.dp))
        PlusBar(plusPriceKopecks, onPlus)
    }
    Spacer(Modifier.height(12.dp))
    val nextAt = parseInstant(status.season?.nextUnlockAt ?: status.nextAt)?.toEpochMilli()
    PassRail(columns, own, nextAt, status.season?.day ?: 0, claiming, onClaim, onPlus, onElapsed)
    Spacer(Modifier.height(12.dp))
    val ready = readyTargets(columns)
    if (ready.isNotEmpty()) {
        MPrimaryButton(
            text = if (ready.size > 1) "Забрать всё (${ready.size})" else "Забрать",
            onClick = { onClaim(if (ready.size > 1) DailyTarget.All else ready.first()) },
            modifier = Modifier.fillMaxWidth(),
            size = MButtonSize.Large,
            loading = claiming,
            icon = MIcons.gift,
        )
    } else if (nextAt != null) {
        NextUnlock(nextAt, onElapsed)
    }
}

@Composable
private fun NextUnlock(at: Long, onElapsed: () -> Unit) {
    val colors = millida
    val now by rememberNow(1_000L)
    val left = at - now
    val elapsed by rememberUpdatedState(onElapsed)
    LaunchedEffect(at) { refreshWhenDue(at) { elapsed() } }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .pixelSurface(colors.inset, MillidaCut.control, colors.plateFrame())
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MIcon(MIcons.clock, size = 16.dp, color = colors.fgSubtle)
        Spacer(Modifier.width(8.dp))
        Text("Новая клетка через ", style = MillidaType.body, color = colors.fgSubtle)
        Text(timerText(left), style = MillidaType.pixel, color = colors.accentText)
    }
}

@Composable
private fun PlusBar(priceKopecks: Long?, onPlus: () -> Unit) {
    val colors = millida
    Row(
        Modifier
            .fillMaxWidth()
            .pixelSurface(Rarity.LEGENDARY.color.mixSrgb(colors.surface2, 0.16f), MillidaCut.plate, colors.plateFrame())
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlusBadge()
        Spacer(Modifier.width(8.dp))
        Text(
            if (priceKopecks != null && priceKopecks > 0) rubles(priceKopecks) + "/мес" else "Золотая строка",
            modifier = Modifier.weight(1f),
            style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MSecondaryButton("Оформить", onClick = onPlus, size = MButtonSize.Small, icon = MIcons.crown)
    }
}

@Composable
private fun PassRail(
    columns: List<PassColumn>,
    own: Boolean,
    nextAt: Long?,
    today: Int,
    busy: Boolean,
    onClaim: (DailyTarget) -> Unit,
    onLocked: () -> Unit,
    onElapsed: () -> Unit,
) {
    val rowState = rememberLazyListState()
    val focus = remember(columns) {
        columns.indexOfFirst { it.ready }.takeIf { it >= 0 }
            ?: columns.indexOfFirst { it.freeState == FUTURE }.takeIf { it >= 0 }
            ?: columns.lastIndex
    }
    LaunchedEffect(focus) {
        if (focus >= 0) rowState.scrollToItem((focus - 1).coerceAtLeast(0))
    }
    val soonDay = columns.firstOrNull { it.freeState == FUTURE }?.day
    Row {
        RowBracket(own)
        Spacer(Modifier.width(6.dp))
        LazyRow(
            modifier = Modifier.weight(1f),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(columns, key = { _, column -> column.day }) { index, column ->
                PassColumnView(
                    column = column,
                    weekStart = index > 0 && index % WEEK == 0,
                    today = column.day == today,
                    soonAt = if (column.day == soonDay) nextAt else null,
                    busy = busy,
                    onClaim = onClaim,
                    onLocked = onLocked,
                    onElapsed = onElapsed,
                )
            }
        }
    }
}

/** A subscriber takes both rows, so the bracket spans both. */
@Composable
private fun RowBracket(own: Boolean) {
    val colors = millida
    val gold = Rarity.LEGENDARY.color
    val total = HEAD_HEIGHT + ROW_GAP + CELL_HEIGHT + ROW_GAP + CELL_HEIGHT
    Box(
        Modifier
            .width(BRACKET_WIDTH)
            .height(total)
            .semantics { contentDescription = if (own) "PLUS забирает обе строки" else "Нижняя строка — PLUS" },
    ) {
        Canvas(Modifier.width(BRACKET_WIDTH).height(total)) {
            val bar = 3.dp.toPx()
            val tick = 8.dp.toPx()
            val x = 4.dp.toPx()
            val freeTop = (HEAD_HEIGHT + ROW_GAP).toPx()
            val plusTop = freeTop + (CELL_HEIGHT + ROW_GAP).toPx()
            val bottom = size.height
            fun bracket(top: Float, end: Float, color: Color) {
                drawRect(color, Offset(x, top), Size(bar, end - top))
                drawRect(color, Offset(x, top), Size(tick, bar))
                drawRect(color, Offset(x, end - bar), Size(tick, bar))
            }
            if (own) {
                bracket(freeTop, bottom, gold)
            } else {
                bracket(freeTop, plusTop - ROW_GAP.toPx(), colors.fgFaint.copy(alpha = 0.35f))
                bracket(plusTop, bottom, gold)
            }
        }
        val crownAt = if (own) HEAD_HEIGHT + ROW_GAP + CELL_HEIGHT - 6.dp else HEAD_HEIGHT + ROW_GAP * 2 + CELL_HEIGHT + CELL_HEIGHT / 2 - 9.dp
        Box(
            Modifier
                .offset(y = crownAt)
                .size(18.dp)
                .pixelSurface(MillidaPixel.gold, MillidaCut.badge, PixelFrame(bottom = MillidaPixel.goldBottom, bottomWidth = 2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(MIcons.crown, size = 12.dp, color = MillidaPixel.goldInk)
        }
    }
}

@Composable
private fun PassColumnView(
    column: PassColumn,
    weekStart: Boolean,
    today: Boolean,
    soonAt: Long?,
    busy: Boolean,
    onClaim: (DailyTarget) -> Unit,
    onLocked: () -> Unit,
    onElapsed: () -> Unit,
) {
    val colors = millida
    Column(
        Modifier
            .padding(start = if (weekStart) WEEK_GAP else 0.dp)
            .width(COLUMN_WIDTH),
    ) {
        val headFill = when {
            column.ready -> colors.accent
            today -> colors.accentToday
            else -> Color.Transparent
        }
        val headInk = when {
            column.ready -> colors.onAccent
            column.freeState == CLAIMED -> colors.fgMuted
            else -> colors.fgFaint
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(HEAD_HEIGHT)
                .pixelSurface(headFill, MillidaCut.badge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (column.freeState == CLAIMED) {
                MIcon(MIcons.check, size = 11.dp, color = headInk)
                Spacer(Modifier.width(3.dp))
            }
            Text(
                "ДЕНЬ ${column.day}",
                style = MillidaType.caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = headInk,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(ROW_GAP))
        PassCell(
            rewards = column.free,
            plus = false,
            state = column.freeState,
            label = "День ${column.day}",
            soonAt = soonAt,
            onClick = when {
                column.freeState == READY && !busy -> ({ onClaim(DailyTarget.Cell(column.day, TrackRow.FREE)) })
                else -> null
            },
            onElapsed = onElapsed,
        )
        Spacer(Modifier.height(ROW_GAP))
        PassCell(
            rewards = column.plus,
            plus = true,
            state = column.plusState,
            label = "PLUS, день ${column.day}",
            soonAt = null,
            onClick = when {
                column.plusState == READY && !busy -> ({ onClaim(DailyTarget.Cell(column.day, TrackRow.PLUS)) })
                column.plusState == LOCKED -> onLocked
                else -> null
            },
            onElapsed = onElapsed,
        )
    }
}

@Composable
private fun PassCell(
    rewards: List<RubyReward>,
    plus: Boolean,
    state: String,
    label: String,
    soonAt: Long?,
    onClick: (() -> Unit)?,
    onElapsed: () -> Unit,
) {
    val colors = millida
    val gold = Rarity.LEGENDARY.color
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (onClick != null) UiSoundEvent.Nav else null)
    val progress = rememberPressProgress(pressed && onClick != null, MillidaMotion.tilePress, MillidaMotion.steps(2))
    val fill = when {
        state == READY && plus -> gold.mixSrgb(colors.surface2, 0.34f)
        state == READY -> colors.accent.mixSrgb(colors.surface2, 0.26f)
        state == CLAIMED -> colors.surface2
        plus -> gold.mixSrgb(colors.surface2, 0.14f)
        else -> colors.surface2
    }
    val artAlpha = when (state) {
        CLAIMED -> 0.35f
        FUTURE -> 0.55f
        else -> 1f
    }
    val hop: State<Float>? = if (state == READY && !LocalReducedMotion.current) {
        rememberInfiniteTransition(label = "cell-hop").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(MillidaMotion.hop, easing = LinearEasing)),
            label = "cell-hop-phase",
        )
    } else {
        null
    }
    val spoken = rewards.joinToString(", ") { it.spoken() }
    val action = when (state) {
        READY -> "Забрать: "
        LOCKED -> "Только с PLUS: "
        CLAIMED -> "Забрано: "
        else -> ""
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(CELL_HEIGHT)
            .pressShift(progress)
            .pixelSurface(fill, MillidaCut.plate, colors.plateFrame())
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = "$action$label — $spoken" },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(CELL_HEIGHT)
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val side = when {
                rewards.size > 1 -> 28.dp
                state == READY -> 44.dp
                else -> 38.dp
            }
            Row(
                Modifier
                    .weight(1f)
                    .graphicsLayer { translationY = hopAt(hop?.value ?: 0f).dp.toPx() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (reward in rewards.take(2)) {
                    RewardGlyph(reward.kind, side = side, tier = reward.tier, item = reward.item, alpha = artAlpha)
                }
            }
            when {
                state == READY -> Box(
                    Modifier
                        .pixelSurface(if (plus) MillidaPixel.gold else colors.accent, MillidaCut.badge)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "Забрать",
                        style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black),
                        color = if (plus) MillidaPixel.goldInk else colors.onAccent,
                        maxLines = 1,
                    )
                }
                soonAt != null -> SoonTag(soonAt, onElapsed)
                else -> Text(
                    rewards.joinToString(" + ") { it.label() }.ifEmpty { "—" },
                    style = MillidaType.tabLabel.copy(fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold),
                    color = when (state) {
                        CLAIMED -> colors.fgFaint
                        FUTURE -> colors.fgMuted
                        else -> colors.fg
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (state) {
            LOCKED -> CornerMark(MIcons.lock, MillidaPixel.gold, MillidaPixel.goldInk)
            CLAIMED -> CornerMark(MIcons.check, colors.accent, colors.onAccent)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerMark(
    icon: net.millida.launcher.ui.MillidaIcon,
    fill: Color,
    ink: Color,
) {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 7.dp, end = 7.dp)
            .size(18.dp)
            .pixelSurface(fill, MillidaCut.badge),
        contentAlignment = Alignment.Center,
    ) {
        MIcon(icon, size = 11.dp, color = ink)
    }
}

@Composable
private fun SoonTag(at: Long, onElapsed: () -> Unit) {
    val colors = millida
    val now by rememberNow(1_000L)
    val left = at - now
    val elapsed by rememberUpdatedState(onElapsed)
    LaunchedEffect(at) { refreshWhenDue(at) { elapsed() } }
    Row(
        Modifier
            .pixelSurface(colors.bg, MillidaCut.badge)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.clock, size = 10.dp, color = colors.accentText)
        Spacer(Modifier.width(3.dp))
        Text(timerText(left), style = MillidaType.pixelSmall.copy(fontSize = 10.sp), color = colors.accentText, maxLines = 1)
    }
}
