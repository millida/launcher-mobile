package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.RewardKind
import net.millida.launcher.chestTierOfName
import net.millida.launcher.core.rubies.DailyStatus
import net.millida.launcher.core.rubies.EconomyProgress
import net.millida.launcher.core.rubies.PackQuest
import net.millida.launcher.core.rubies.QuestState
import net.millida.launcher.core.rubies.WeeklyPath
import net.millida.launcher.core.rubies.questProgress
import net.millida.launcher.core.rubies.questState
import net.millida.launcher.core.rubies.PlaytimeReward
import net.millida.launcher.core.rubies.PlusStatus
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.WeeklyParcel
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MRarityFrame
import net.millida.launcher.ui.MRarityPlate
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import java.util.Locale

private val CHEST_TIERS = listOf("LEGEND", "EPIC", "RARE", "COMMON")

internal fun minuteWord(count: Int): String {
    val tens = kotlin.math.abs(count) % 100
    val ones = tens % 10
    return when {
        tens in 11..19 -> "минут"
        ones == 1 -> "минута"
        ones in 2..4 -> "минуты"
        else -> "минут"
    }
}

internal fun hoursText(value: Double): String {
    val text = String.format(Locale.forLanguageTag("ru"), "%.1f", value)
    return if (text.contains(',')) text.trimEnd('0').trimEnd(',') else text
}

@Composable
internal fun WeeklyCard(parcel: WeeklyParcel, busy: Boolean, onPick: (RubyItem) -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Посылка недели", modifier = Modifier.weight(1f), style = MillidaType.sectionTitle, color = colors.fg)
            dayMonth(parcel.resetsAt)?.let { Text("до $it", style = MillidaType.small, color = colors.fgFaint, maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
        val choices = parcel.choices.orEmpty()
        when {
            parcel.claimedAt != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.check, size = 16.dp, color = colors.accentText)
                Spacer(Modifier.width(8.dp))
                Text("Забрана — новая с понедельника", style = MillidaType.body, color = colors.fgMuted)
            }
            parcel.ready && choices.isNotEmpty() -> {
                Text("Выбери одну вещь навсегда", style = MillidaType.body, color = colors.fgMuted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in choices.take(3)) {
                        MRarityFrame(
                            rarity = Rarity.of(item.rarity),
                            modifier = Modifier.weight(1f),
                            onClick = if (busy) null else ({ onPick(item) }),
                            contentPadding = 8.dp,
                        ) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                ItemArt(item, side = 60.dp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    item.name,
                                    style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                                    color = colors.fg,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                val need = if (parcel.need > 0) parcel.need else 3.0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Играй ${hoursText(need)} ч за неделю", modifier = Modifier.weight(1f), style = MillidaType.body, color = colors.fgMuted)
                    Text("${hoursText(parcel.hours)}/${hoursText(need)} ч", style = MillidaType.pixel, color = colors.fg)
                }
                Spacer(Modifier.height(8.dp))
                MProgressBar((parcel.hours / need).toFloat())
            }
        }
    }
}

@Composable
internal fun ProgressCard(
    progress: EconomyProgress,
    isBusy: (String) -> Boolean,
    onClaim: (PlaytimeReward) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 10.dp) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Награды за игру", modifier = Modifier.weight(1f), style = MillidaType.sectionTitle, color = colors.fg)
            Text("${hoursText(progress.hours)} ч в игре", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        val rows = progress.items
        rows.forEachIndexed { index, reward ->
            if (index > 0) MDivider()
            val tier = chestTierOfName(reward.chest)
            val prize = when {
                tier != null -> chestName(tier) + " сундук"
                reward.rubies > 0 -> "${groupDigits(reward.rubies)} ${rubyWord(reward.rubies)}"
                else -> ""
            }
            MListRow(
                title = reward.title,
                subtitle = listOf("${reward.hoursDone}/${reward.hours} ч", prize).filter { it.isNotEmpty() }.joinToString(" · "),
                plate = false,
                leading = {
                    Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                        if (tier != null) ChestArt(tier, side = 40.dp) else MIcon(MIcons.ruby, size = 32.dp)
                    }
                },
                trailing = {
                    when {
                        reward.claimed -> MBadge("Есть", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
                        reward.done -> MSecondaryButton(
                            "Забрать",
                            onClick = { onClaim(reward) },
                            size = MButtonSize.Small,
                            loading = isBusy("progress:" + reward.code),
                        )
                        else -> MIcon(MIcons.lock, size = 16.dp, color = colors.fgFaint)
                    }
                },
            )
        }
        progress.hourItems.forEachIndexed { index, entry ->
            if (rows.isNotEmpty() || index > 0) MDivider()
            MListRow(
                title = entry.item.name,
                subtitle = "За ${entry.hours} ч в игре",
                plate = false,
                leading = { ItemArt(entry.item, side = 44.dp) },
                trailing = {
                    if (entry.owned) {
                        MBadge("Есть", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
                    } else {
                        Text("${hoursText(progress.hours)}/${entry.hours}", style = MillidaType.pixelSmall, color = colors.fgSubtle)
                    }
                },
            )
        }
    }
}

/** What the gold row gives over a season, counted from the track itself, never from a hardcoded promise. */
internal class PlusTotals(
    val chests: Map<String, Int>,
    val shards: Long,
    val rubies: Long,
    val items: Int,
    val fragments: Long,
) {
    val empty: Boolean get() = chests.isEmpty() && shards == 0L && rubies == 0L && items == 0 && fragments == 0L
}

internal fun plusTotals(daily: DailyStatus?): PlusTotals {
    val chests = LinkedHashMap<String, Int>()
    var shards = 0L
    var rubies = 0L
    var items = 0
    var fragments = 0L
    for (day in daily?.track.orEmpty()) {
        for (reward in day.plus) {
            when (reward.kind) {
                RewardKind.CHEST -> chests[reward.tier.uppercase()] = (chests[reward.tier.uppercase()] ?: 0) + 1
                RewardKind.SHARDS -> shards += reward.amount
                RewardKind.RUBIES -> rubies += reward.amount
                RewardKind.ITEM -> items += 1
                RewardKind.FRAGMENTS -> fragments += reward.amount
            }
        }
    }
    return PlusTotals(chests, shards, rubies, items, fragments)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlusPerks(totals: PlusTotals, shardBoost: Double) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (tier in CHEST_TIERS) {
            val count = totals.chests[tier] ?: 0
            if (count > 0) Perk(label = chestName(tier), value = "×$count") { ChestArt(tier, side = 40.dp) }
        }
        if (totals.items > 0) Perk(label = "навсегда", value = "×${totals.items}") { MIcon(MIcons.gift, size = 34.dp) }
        if (totals.rubies > 0) Perk(label = rubyWord(totals.rubies), value = "+" + groupDigits(totals.rubies)) { MIcon(MIcons.ruby, size = 34.dp) }
        if (totals.shards > 0) Perk(label = shardWord(totals.shards), value = "+" + groupDigits(totals.shards)) { MIcon(MIcons.shard, size = 34.dp) }
        if (totals.fragments > 0) {
            Perk(label = fragmentWord(totals.fragments), value = "+" + groupDigits(totals.fragments)) { MIcon(MIcons.sparkle, size = 30.dp) }
        }
        if (shardBoost > 1.0) Perk(label = "к осколкам", value = "×" + hoursText(shardBoost)) { MIcon(MIcons.zap, size = 30.dp) }
    }
}

@Composable
private fun Perk(label: String, value: String? = null, art: @Composable () -> Unit) {
    val colors = millida
    Column(
        Modifier
            .width(86.dp)
            .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) { art() }
        if (value != null) Text(value, style = MillidaType.pixel, color = colors.fg, maxLines = 1)
        Text(label, style = MillidaType.small.copy(fontSize = 11.5.sp), color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ColumnScope.PlusOffer(
    plus: PlusStatus?,
    daily: DailyStatus?,
    busy: Boolean,
    onSubscribe: () -> Unit,
    onManage: () -> Unit,
) {
    val colors = millida
    val totals = plusTotals(daily)
    val active = plus?.active == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlusBadge()
        Spacer(Modifier.width(10.dp))
        val until = dayMonth(plus?.paidUntil)
        Text(
            when {
                active && plus?.canceled == true && until != null -> "Продления не будет · до $until"
                active && until != null -> "Активна до $until"
                else -> "Золотая строка бонуса"
            },
            modifier = Modifier.weight(1f),
            style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            maxLines = 2,
        )
    }
    if (!totals.empty || (plus?.shardBoost ?: 0.0) > 1.0) {
        Spacer(Modifier.height(10.dp))
        Text("За сезон", style = MillidaType.caption, color = colors.fgSubtle)
        Spacer(Modifier.height(6.dp))
        PlusPerks(totals, plus?.shardBoost ?: 0.0)
    }
    Spacer(Modifier.height(12.dp))
    if (!active) {
        val price = plus?.priceKopecks ?: 0L
        MPrimaryButton(
            if (price > 0) "Оформить за ${rubles(price)}" else "Оформить",
            onClick = onSubscribe,
            modifier = Modifier.fillMaxWidth(),
            size = MButtonSize.Large,
            loading = busy,
            icon = MIcons.crown,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Подписка на месяц · отмена в любой день",
            modifier = Modifier.fillMaxWidth(),
            style = MillidaType.small,
            color = colors.fgFaint,
            textAlign = TextAlign.Center,
        )
    }
    if (active && plus?.canceled != true) {
        MTextButton("Отменить PLUS", onClick = onManage, color = colors.fgSubtle)
    }
}

@Composable
internal fun PlusCard(
    plus: PlusStatus?,
    daily: DailyStatus?,
    busy: Boolean,
    onSubscribe: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(
        modifier = modifier,
        background = Rarity.LEGENDARY.color.mixSrgb(colors.surface, 0.10f),
        contentPadding = 14.dp,
    ) {
        PlusOffer(plus, daily, busy, onSubscribe, onManage)
    }
}

/** Half of an item's fragments for playing a pack; the rest is bought. */
@Composable
internal fun QuestsCard(
    quests: List<PackQuest>,
    isBusy: (String) -> Boolean,
    onClaim: (PackQuest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 10.dp) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Задания сборок", modifier = Modifier.weight(1f), style = MillidaType.sectionTitle, color = colors.fg)
            MBadge("Вещь целиком — за игру", color = colors.fgSubtle, background = colors.chipFill, icon = MIcons.clock)
        }
        Spacer(Modifier.height(4.dp))
        quests.forEachIndexed { index, quest ->
            if (index > 0) MDivider()
            QuestRow(quest, busy = isBusy("quest:" + quest.code), onClaim = { onClaim(quest) })
        }
    }
}

@Composable
private fun QuestRow(quest: PackQuest, busy: Boolean, onClaim: () -> Unit) {
    val colors = millida
    val state = questState(quest)
    val rarity = Rarity.of(quest.item.rarity)
    val got = state == QuestState.Claimed || state == QuestState.Owned
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ItemArt(quest.item, side = 48.dp, modifier = if (got) Modifier.alphaFaded() else Modifier)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(quest.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MProgressBar(questProgress(quest), color = rarity?.color ?: colors.accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    quest.item.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (rarity != null) {
                    Spacer(Modifier.width(6.dp))
                    MRarityPlate(rarity)
                }
            }
            Text(
                "${minOf(quest.minutes, quest.needMinutes)}/${quest.needMinutes} мин",
                style = MillidaType.pixelSmall,
                color = colors.fgSubtle,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (state) {
            QuestState.Claim -> MPrimaryButton("Забрать", onClick = onClaim, size = MButtonSize.Small, loading = busy)
            is QuestState.Play -> Text(
                "Ещё ${state.minutesLeft} ${minuteWord(state.minutesLeft)} в ${quest.packName}",
                modifier = Modifier.width(110.dp),
                style = MillidaType.small,
                color = colors.fgSubtle,
                textAlign = TextAlign.End,
                maxLines = 3,
            )
            QuestState.Owned -> MBadge("Есть", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
            QuestState.Claimed -> MBadge("Забрано", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
        }
    }
}

private fun Modifier.alphaFaded(): Modifier = this.then(Modifier.graphicsLayer { alpha = 0.55f })

/**
 * The weekly star path: tasks give stars, stars open shard steps and the item
 * of the week. Only the last stretch can be bought, after a confirmation.
 */
@Composable
internal fun WeeklyPathCard(
    path: WeeklyPath,
    claimBusy: Boolean,
    boostBusy: Boolean,
    onClaim: (Int) -> Unit,
    onBoost: () -> Unit,
    onElapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val prize = path.prize
    val rarity = Rarity.of(prize.rarity)
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Задания недели", modifier = Modifier.weight(1f), style = MillidaType.sectionTitle, color = colors.fg)
            if (path.resetsAt.isNotBlank()) ShelfTimer("Новые через", path.resetsAt, onElapsed)
        }
        Spacer(Modifier.height(10.dp))
        MRarityFrame(rarity = rarity, modifier = Modifier.fillMaxWidth(), contentPadding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemArt(prize, side = 84.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ВЕЩЬ НЕДЕЛИ", style = MillidaType.caption, color = colors.fgSubtle, maxLines = 1)
                    Text(prize.name, style = MillidaType.rowTitle.copy(fontSize = 15.sp), color = colors.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (rarity != null) MRarityPlate(rarity)
                        if (path.prizePrice > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("в магазине ", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
                            Text(
                                groupDigits(path.prizePrice),
                                style = MillidaType.small.copy(textDecoration = TextDecoration.LineThrough),
                                color = colors.fgFaint,
                                maxLines = 1,
                            )
                        }
                    }
                    val need = path.need.coerceAtLeast(1)
                    MProgressBar(path.stars.toFloat() / need, color = Rarity.LEGENDARY.color)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${path.stars}/${path.need}", style = MillidaType.pixel, color = colors.fg)
                        Spacer(Modifier.width(4.dp))
                        MIcon(MIcons.star, size = 14.dp, color = Rarity.LEGENDARY.color)
                        Spacer(Modifier.width(4.dp))
                        Text("— твоя", style = MillidaType.small, color = colors.fgSubtle)
                    }
                    val step = path.prizeStep
                    val boost = path.boost
                    when {
                        path.owned -> MBadge("Твоя", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
                        path.done && step != null -> MPrimaryButton(
                            "Забрать",
                            onClick = { onClaim(step.at) },
                            modifier = Modifier.fillMaxWidth(),
                            loading = claimBusy,
                        )
                        boost != null && boost.available && boost.missing > 0 -> MSecondaryButton(
                            "Докупить ${boost.missing} за ${groupDigits(boost.price)}",
                            onClick = onBoost,
                            modifier = Modifier.fillMaxWidth(),
                            icon = MIcons.star,
                            loading = boostBusy,
                        )
                    }
                }
            }
        }
        val shardSteps = path.steps.filter { it.reward.kind != RewardKind.ITEM }
        if (shardSteps.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (step in shardSteps) {
                    val reached = path.stars >= step.at
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MIcon(MIcons.star, size = 12.dp, color = if (reached) Rarity.LEGENDARY.color else colors.fgFaint)
                            Spacer(Modifier.width(3.dp))
                            Text("${step.at}", style = MillidaType.pixelSmall, color = colors.fgSubtle)
                        }
                        when {
                            step.claimed -> MBadge("+${step.reward.amount}", color = colors.accentText, background = colors.accentSoft, icon = MIcons.check)
                            reached -> MSecondaryButton(
                                "+${step.reward.amount}",
                                onClick = { onClaim(step.at) },
                                icon = MIcons.shard,
                                size = MButtonSize.Small,
                                loading = claimBusy,
                            )
                            else -> MBadge("+${step.reward.amount}", color = colors.fgSubtle, background = colors.chipFill, icon = MIcons.shard)
                        }
                    }
                }
            }
        }
        if (path.tasks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            path.tasks.forEachIndexed { index, task ->
                if (index > 0) MDivider()
                val full = task.earned >= task.stars
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MIcon(if (full) MIcons.check else MIcons.star, size = 16.dp, color = if (full) colors.accentText else colors.fgSubtle)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.title, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fg, maxLines = 2)
                        if (!full && task.target > 0) MProgressBar((task.progress / task.target).toFloat())
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!full) {
                        val unit = if (task.unit.isNotBlank()) " " + task.unit else ""
                        Text("${hoursText(task.progress)}/${hoursText(task.target)}$unit", style = MillidaType.pixelSmall, color = colors.fgSubtle)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("+${task.stars}", style = MillidaType.pixel, color = if (full) colors.fgFaint else colors.fg)
                    MIcon(MIcons.star, size = 12.dp, color = if (full) colors.fgFaint else Rarity.LEGENDARY.color)
                }
            }
        }
        if (path.plusHoursBoost > 0) {
            Spacer(Modifier.height(6.dp))
            MBadge(
                if (path.plus) "PLUS: часы ×${hoursText(path.plusHoursBoost)}" else "С PLUS часы ×${hoursText(path.plusHoursBoost)}",
                color = if (path.plus) MillidaPixel.goldInk else colors.fgSubtle,
                background = if (path.plus) MillidaPixel.gold else colors.chipFill,
                icon = MIcons.crown,
            )
        }
    }
}
