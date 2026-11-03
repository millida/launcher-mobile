package net.millida.launcher.ui.shop

import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.shop.chest.ChestShot
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import net.millida.launcher.core.rubies.CaseContents
import net.millida.launcher.core.rubies.CaseFeedDrop
import net.millida.launcher.core.rubies.CaseMode
import net.millida.launcher.core.rubies.CasePrize
import net.millida.launcher.core.rubies.CasesDay
import net.millida.launcher.core.rubies.ShopCase
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCardRail
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface

private const val CONTENT_GROUPS_SHOWN = 60
private const val HEADS_URL = "https://api.millida.net/v2/heads/avatar/"

internal class CaseChest(val tier: String, val tint: Int?)

/** Each case wears one of the chest models recoloured to its own tint, the desktop's map; an unknown case is the common chest. */
internal fun caseChest(id: String): CaseChest = when (id.lowercase()) {
    "shards", "wings" -> CaseChest("COMMON", 0xE9EEFF)
    "flame" -> CaseChest("RARE", 0xFF6A1A)
    "dark" -> CaseChest("EPIC", null)
    "future" -> CaseChest("RARE", null)
    "pets" -> CaseChest("RARE", 0xFFC93D)
    "emotes" -> CaseChest("EPIC", 0xFF5FA8)
    "mythic" -> CaseChest("RARE", 0xFF2D55)
    else -> CaseChest("COMMON", null)
}

internal fun caseTone(case: ShopCase): Color = serviceColor(case.color, Rarity.COMMON.color)

private fun pityTitle(rarity: String): String = when (rarity.uppercase()) {
    "RARE" -> tr("Гарант редкой")
    "EPIC" -> tr("Гарант эпической")
    "LEGENDARY" -> tr("Гарант легендарной")
    "MYTHIC" -> tr("Гарант мифической")
    else -> tr("Гарант")
}

@Composable
private fun PityBar(case: ShopCase) {
    if (case.pity <= 1) return
    val colors = millida
    val done = (case.pity - case.pityLeft).coerceIn(0, case.pity)
    val now = case.pityLeft <= 1
    val lit = if (now) case.pity else done
    val tone = Rarity.of(case.pityFrom)?.color ?: colors.accent
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = tr("{0}: {done} из {pity}", pityTitle(case.pityFrom), done, case.pity) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (now) tr("Гарант сейчас") else pityTitle(case.pityFrom),
                modifier = Modifier.weight(1f),
                style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                color = if (now) tone else colors.fgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$lit/${case.pity}", style = MillidaType.pixelSmall, color = colors.fgSubtle, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(case.pity) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .pixelSurface(if (index < lit) tone else colors.inset, 1.dp),
                )
            }
        }
    }
}

@Composable
private fun GuaranteeChip(case: ShopCase, modifier: Modifier = Modifier) {
    val guarantee = case.guarantee ?: return
    val label = guarantee.label.trim().removeSuffix(" ящике")
    if (label.isEmpty() || guarantee.label == case.title) return
    val tone = Rarity.of(guarantee.minRarity)?.color ?: millida.accent
    Box(
        modifier
            .pixelSurface(tone.mixSrgb(millida.surface2, 0.24f), MillidaCut.badge, PixelFrame(outline = tone, outlineWidth = 1.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(label, style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black), color = tone, maxLines = 1)
    }
}

internal class CaseActs(
    val balance: Long?,
    val shards: Long?,
    val busy: Boolean,
    val ofDayPct: Int,
    val refreshAt: String,
    val onOpen: (ShopCase, CaseMode) -> Unit,
    val onLook: (ShopCase) -> Unit,
    val onEnd: () -> Unit,
)

/** The desktop's case card: the chest in its colour, the guarantee chip, the pity bar and the price, ×10 beside it. */
@Composable
internal fun CaseCard(case: ShopCase, free: Boolean, acts: CaseActs, modifier: Modifier = Modifier) {
    val colors = millida
    val tone = caseTone(case)
    val done = case.exhausted
    val wallet = if (case.paidInShards) acts.shards else acts.balance
    Column(
        modifier
            .pixelSurface(
                tone.mixSrgb(colors.surface, 0.12f),
                MillidaCut.plate,
                PixelFrame(outline = if (case.ofDay || free) tone else tone.mixSrgb(colors.surface, 0.45f), outlineWidth = 2.dp, bottom = MillidaPixel.tileBottom, bottomWidth = 4.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .clickable(
                        remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !acts.busy && !done,
                        role = Role.Button,
                        onClickLabel = tr("Ящик «{title}»", case.title),
                    ) { acts.onOpen(case, if (free) CaseMode.FREE else CaseMode.ONE) },
                contentAlignment = Alignment.Center,
            ) {
                caseChest(case.id).let { look -> ChestShot(look.tier, look.tint, Modifier.size(100.dp), alpha = if (done) 0.5f else 1f) }
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .pixelSurface(colors.bg.copy(alpha = MillidaPixel.railArrowAlpha), MillidaCut.badge)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = tr("Что внутри")) { acts.onLook(case) }
                    .padding(7.dp),
            ) { MIcon(MIcons.eye, size = 16.dp, color = colors.fg) }
            if (case.ofDay) {
                Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (acts.ofDayPct > 0) DiscountBadge(acts.ofDayPct)
                    SecClock(acts.refreshAt, acts.onEnd)
                }
            }
        }
        GuaranteeChip(case)
        Text(case.title, style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PityBar(case)
        when {
            done -> MPrimaryButton(tr("Всё собрано"), onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false, icon = MIcons.check, size = MButtonSize.Small)
            free -> MPrimaryButton(
                tr("Открыть бесплатно"),
                onClick = { acts.onOpen(case, CaseMode.FREE) },
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.gift,
                size = MButtonSize.Small,
                enabled = !acts.busy,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PriceButton(
                    case.price,
                    if (case.basePrice > case.price) case.basePrice else 0L,
                    wallet,
                    busy = false,
                    onClick = { acts.onOpen(case, CaseMode.ONE) },
                    shards = case.paidInShards,
                    enabled = !acts.busy,
                )
                val multi = case.multi
                if (multi != null && multi.count > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("×${multi.count}", style = MillidaType.pixel, color = colors.fg, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        PriceButton(
                            multi.price,
                            multi.basePrice,
                            wallet,
                            busy = false,
                            onClick = { acts.onOpen(case, CaseMode.TEN) },
                            modifier = Modifier.weight(1f),
                            shards = case.paidInShards,
                            enabled = !acts.busy,
                        )
                    }
                }
            }
        }
    }
}

/** «Ящики»: the grid of cases and the ticker of recent drops under it. */
@Composable
internal fun CasesGrid(day: CasesDay?, columns: Int, acts: CaseActs, modifier: Modifier = Modifier) {
    if (day == null) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(columns) { MSkeleton(height = 300.dp, modifier = Modifier.weight(1f)) }
        }
        return
    }
    val freeId = day.freeCaseId
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        day.cases.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { case -> CaseCard(case, case.id == freeId, acts, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (day.drops.isNotEmpty()) DropsTicker(day.drops)
    }
}

@Composable
private fun DropsTicker(drops: List<CaseFeedDrop>) {
    val colors = millida
    MCardRail(
        entries = drops.withIndex().toList(),
        key = { it.index },
        label = tr("Недавние выпадения"),
        cardWidth = 170.dp,
        auto = true,
    ) { (_, drop) ->
        val tone = Rarity.of(drop.item.rarity)?.color ?: colors.fgSubtle
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pixelSurface(tone.mixSrgb(colors.surface2, 0.14f), MillidaCut.control)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MAvatar(url = HEADS_URL + java.net.URLEncoder.encode(drop.nick, "UTF-8") + "?size=32", size = 28.dp, icon = MIcons.user)
            Spacer(Modifier.width(6.dp))
            Text(drop.nick, modifier = Modifier.weight(1f), style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ItemArt(drop.item, side = 32.dp)
        }
    }
}

private fun prizeLine(prizes: List<CasePrize>): String = prizes.groupBy { it.kind }.entries.joinToString(" / ") { (kind, list) ->
    val low = list.minOf { it.min }
    val high = list.maxOf { it.max }
    val word = if (kind == "rubies") tr("рубины") else tr("осколки")
    if (low == high) "$word $low" else "$word $low–$high"
}

/** One tile per base item in «Что внутри»: its colourways are counted, not repeated. */
private class ContentGroup(val base: String, val first: net.millida.launcher.core.rubies.CaseContentItem, val ways: Int, val owned: Boolean, val rank: Int)

private fun groupContents(contents: CaseContents): List<ContentGroup> =
    contents.items.groupBy { it.item.code.substringBefore('~') }.map { (base, list) ->
        ContentGroup(base, list.first(), list.size, list.all { it.owned }, list.maxOf { Rarity.of(it.item.rarity)?.ordinal ?: 0 })
    }.sortedWith(compareByDescending<ContentGroup> { it.rank }.thenByDescending { it.ways })

/** «Что внутри»: what the case drops, rarest first, and what comes instead of an item when luck is out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaseContentsSheet(case: ShopCase, load: suspend (String) -> CaseContents, onDismiss: () -> Unit) {
    val colors = millida
    var data by remember(case.id) { mutableStateOf<CaseContents?>(null) }
    var failed by remember(case.id) { mutableStateOf(false) }
    var attempt by remember(case.id) { mutableIntStateOf(0) }
    LaunchedEffect(case.id, attempt) {
        failed = false
        try {
            data = load(case.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed = true
        }
    }
    MBottomSheet(onDismiss = onDismiss, title = case.title) {
        val consolation = data?.consolation?.takeIf { it.isNotEmpty() } ?: case.consolation
        if (consolation.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pixelSurface(colors.surface2, MillidaCut.control)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MIcon(MIcons.shard, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                val prize = prizeLine(consolation)
                val line = if (case.cheapItem || !data?.items.isNullOrEmpty()) tr("Не повезло — всё равно шмотка + {0}", prize) else tr("Не повезло — всё равно {0}", prize)
                Text(line, style = MillidaType.small, color = colors.fgMuted)
            }
            Spacer(Modifier.height(10.dp))
        }
        val loaded = data
        when {
            failed -> MErrorCard(tr("Состав ящика не загрузился — нажми «Повторить»"), { attempt += 1 })
            loaded == null -> MSkeleton(height = 220.dp)
            else -> {
                val groups = groupContents(loaded)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.take(CONTENT_GROUPS_SHOWN).forEach { group -> ContentTile(group) }
                }
                if (groups.size > CONTENT_GROUPS_SHOWN) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("И ещё {0}", groups.size - CONTENT_GROUPS_SHOWN), style = MillidaType.small, color = colors.fgSubtle)
                }
            }
        }
    }
}

@Composable
private fun ContentTile(group: ContentGroup) {
    val colors = millida
    val item = group.first.item
    val rarity = Rarity.of(item.rarity)
    Column(
        Modifier
            .width(96.dp)
            .pixelSurface((rarity?.color ?: colors.fgSubtle).mixSrgb(colors.surface2, 0.14f), MillidaCut.control)
            .alpha(if (group.owned) 0.55f else 1f)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            ItemArt(item, side = 64.dp)
            if (group.owned) MIcon(MIcons.check, size = 14.dp, color = colors.accentText)
        }
        Text(item.name, style = MillidaType.small.copy(fontSize = 11.5.sp), color = colors.fg, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        if (group.ways > 1) Text(tr("{ways} расцв.", group.ways), style = MillidaType.tabLabel, color = colors.fgSubtle, maxLines = 1)
    }
}

/** The confirmation line under a case purchase: one or ten for the price of nine, in rubies or shards. */
internal fun casePriceText(case: ShopCase, mode: CaseMode): String {
    val price = case.priceOf(mode)
    return groupDigits(price) + " " + if (case.paidInShards) shardWord(price) else rubyWord(price)
}
