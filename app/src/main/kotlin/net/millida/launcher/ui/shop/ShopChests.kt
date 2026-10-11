package net.millida.launcher.ui.shop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.rubies.ChestOdds
import net.millida.launcher.core.rubies.PendingChest
import net.millida.launcher.core.rubies.PityLeft
import net.millida.launcher.core.rubies.RubyRules
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.hopAt
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CHEST_CELL_WIDTH = 100.dp
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))
private val PLAYTIME_SOURCE = Regex("^playtime:hours(\\d+)$", RegexOption.IGNORE_CASE)
private const val ODDS_LOADING = "Шансы загружаются…"
private const val ODDS_FAILED = "Шансы не загрузились — нажми «Повторить»"

internal fun chestSource(chest: PendingChest): String {
    val source = chest.source.trim()
    PLAYTIME_SOURCE.find(source)?.let { return it.groupValues[1] + " ч игры" }
    val date = parseInstant(chest.createdAt)?.let { SHORT_DATE.format(it.atZone(ZoneId.of("Europe/Moscow"))).replace(".", "") }.orEmpty()
    return when (source.uppercase()) {
        "DAILY" -> if (chest.day > 0) "Бонус · день ${chest.day}" else "Бонус · $date"
        "DAILY_PLUS" -> if (chest.day > 0) "PLUS · день ${chest.day}" else "PLUS · $date"
        "CREATOR", "CREATOR_CODE" -> "Код автора"
        "PROMO" -> "Промокод"
        "STREAK" -> "Серия входов"
        "XRAY" -> "Рентген"
        else -> date.ifEmpty { "Подарок" }
    }
}

internal fun percentOfBasisPoints(bp: Int): String = percentText(bp / 100.0, if (bp < 10) 2 else 1)

internal fun percentText(value: Double, digits: Int = 1): String {
    val text = String.format(Locale.forLanguageTag("ru"), "%.${digits}f", value)
    val trimmed = if (text.contains(',')) text.trimEnd('0').trimEnd(',') else text
    return "$trimmed %"
}

@Composable
internal fun MyChestsCard(
    chests: List<PendingChest>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onRetry: () -> Unit,
    onOpen: (List<PendingChest>) -> Unit,
    onOdds: () -> Unit,
    onHowTo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Мои сундуки",
                modifier = Modifier.weight(1f, fill = false),
                style = MillidaType.sectionTitle,
                color = colors.fg,
                maxLines = 1,
            )
            if (chests.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                MBadge(chests.size.toString(), color = colors.onAccent, background = colors.accent)
            }
            Spacer(Modifier.weight(1f))
            MTextButton("Шансы", onClick = onOdds, icon = MIcons.info)
        }
        Spacer(Modifier.height(8.dp))
        when {
            chests.isEmpty() && loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { MSkeleton(height = 128.dp, modifier = Modifier.width(CHEST_CELL_WIDTH)) }
            }
            chests.isEmpty() && error == null -> EmptyChests(onHowTo)
            chests.isNotEmpty() -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chests, key = { it.id }) { chest ->
                        ChestCell(chest, enabled = !busy, modifier = Modifier.animateItem()) { onOpen(listOf(chest)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                MPrimaryButton(
                    text = if (chests.size > 1) "Открыть все (${chests.size})" else "Открыть",
                    onClick = { onOpen(chests) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    icon = MIcons.chest,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            MErrorCard(error, onRetry)
        }
    }
}

@Composable
private fun EmptyChests(onHowTo: () -> Unit) {
    val colors = millida
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ChestArt("COMMON", side = 56.dp, alpha = 0.45f)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Сундуков пока нет", style = MillidaType.rowTitle, color = colors.fg)
            MTextButton("Как получить", onClick = onHowTo, icon = MIcons.chevronRight, color = colors.accentText)
        }
    }
}

@Composable
private fun ChestCell(chest: PendingChest, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = millida
    val tone = chestTone(chest.tier)
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Nav)
    val progress = rememberPressProgress(pressed && enabled, MillidaMotion.tilePress, MillidaMotion.steps(2))
    val hop: State<Float>? = if (LocalReducedMotion.current) {
        null
    } else {
        rememberInfiniteTransition(label = "chest-hop").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(MillidaMotion.hop, easing = LinearEasing)),
            label = "chest-hop-phase",
        )
    }
    Column(
        modifier
            .width(CHEST_CELL_WIDTH)
            .pressShift(progress, MillidaMotion.tilePressOffset)
            .pixelSurface(tone.mixSrgb(colors.surface2, 0.18f), MillidaCut.plate, colors.plateFrame())
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "Открыть",
                onClick = onClick,
            )
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChestArt(
            chest.tier,
            side = 60.dp,
            modifier = Modifier.graphicsLayer { translationY = hopAt(hop?.value ?: 0f).dp.toPx() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            chestName(chest.tier),
            style = MillidaType.rowTitle.copy(fontSize = 13.sp, lineHeight = 16.sp),
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            chestSource(chest),
            style = MillidaType.small.copy(fontSize = 11.5.sp, lineHeight = 14.sp),
            color = colors.fgFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Odds are disclosed before a chest is opened. */
@Composable
internal fun OddsPanel(
    tier: String,
    odds: ChestOdds?,
    pityAfter: Int,
    pityLegendAfter: Int,
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (odds == null && failed) {
        MErrorCard(ODDS_FAILED, onRetry, modifier)
        return
    }
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Text("Что внутри", style = MillidaType.sectionTitle, color = millida.fg)
        Spacer(Modifier.height(8.dp))
        if (odds == null) {
            Text(ODDS_LOADING, style = MillidaType.body, color = millida.fgSubtle)
        } else {
            OddsBody(odds, pityAfter, pityLegendAfter)
        }
    }
}

@Composable
private fun OddsBody(odds: ChestOdds, pityAfter: Int, pityLegendAfter: Int) {
    val colors = millida
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shards = odds.shards
        if (shards != null && shards.max > 0) {
            OddsLine(MIcons.shard, "${shards.min}–${shards.max} ${shardWord(shards.max.toLong())}", "вещей: ${odds.items}")
        }
        val rubies = odds.rubies
        if (rubies != null && rubies.max > 0) {
            OddsLine(MIcons.ruby, "${rubies.min}–${rubies.max} ${rubyWord(rubies.max.toLong())}", percentText(rubies.pct))
        }
        val chances = odds.chances
            .filter { it.bp > 0 }
            .sortedBy { Rarity.of(it.rarity)?.ordinal ?: Int.MAX_VALUE }
        for (chance in chances) {
            val rarity = Rarity.of(chance.rarity)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .pixelSurface(rarity?.color ?: colors.fgFaint, 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    rarity?.title ?: chance.title,
                    modifier = Modifier.weight(1f),
                    style = MillidaType.body,
                    color = colors.fg,
                    maxLines = 1,
                )
                Text(percentOfBasisPoints(chance.bp), style = MillidaType.pixel, color = colors.fg)
            }
        }
        val foot = buildList {
            if (pityAfter > 0) add("эпическая и выше — раз в $pityAfter")
            if (pityLegendAfter > 0) add("легендарная и выше — раз в $pityLegendAfter ${chestWord(pityLegendAfter.toLong())}")
            if (odds.wholePct > 0) add("целая вещь — ${percentText(odds.wholePct)}, иначе фрагменты")
        }
        if (foot.isNotEmpty()) {
            Text(
                "Гарантия: " + foot.joinToString(", ") + ".",
                style = MillidaType.small,
                color = colors.fgFaint,
            )
        }
    }
}

@Composable
private fun OddsLine(icon: net.millida.launcher.ui.MillidaIcon, left: String, right: String) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(left, modifier = Modifier.weight(1f), style = MillidaType.body.copy(fontSize = 13.5.sp), color = colors.fg, maxLines = 1)
        Text(right, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
    }
}

@Composable
internal fun OddsSheet(rules: RubyRules?, pity: PityLeft?, failed: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss, title = "Шансы сундуков") {
        val items = rules?.chests?.items.orEmpty()
        when {
            items.isNotEmpty() -> Unit
            rules == null && failed -> MErrorCard(ODDS_FAILED, onRetry)
            rules == null -> Text(ODDS_LOADING, style = MillidaType.body, color = colors.fgSubtle)
            else -> Text("Шансы ещё не загрузились — открой позже", style = MillidaType.body, color = colors.fgSubtle)
        }
        for (odds in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChestArt(odds.tier, side = 40.dp)
                Spacer(Modifier.width(10.dp))
                Text(chestName(odds.tier) + " сундук", style = MillidaType.rowTitle, color = colors.fg)
            }
            Spacer(Modifier.height(6.dp))
            OddsBody(odds, rules?.chests?.pityAfter ?: 0, rules?.chests?.pityLegendAfter ?: 0)
            Spacer(Modifier.height(16.dp))
        }
        if (pity != null && (pity.epic > 0 || pity.legend > 0)) {
            Text(
                "До эпической ${pity.epic} · до легендарной ${pity.legend}",
                style = MillidaType.small,
                color = colors.accentText,
            )
        }
    }
}
