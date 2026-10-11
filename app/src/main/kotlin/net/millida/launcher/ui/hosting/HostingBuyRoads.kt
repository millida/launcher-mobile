package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.HostingPanel
import net.millida.launcher.core.hosting.HostingPlan
import net.millida.launcher.ui.HostingFact
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MHeroTone
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.hostingGb
import net.millida.launcher.ui.hostingRubles
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.rememberShimmer
import net.millida.launcher.ui.shimmer

internal data class HostingPlanFacts(
    val free: HostingPlan?,
    val paid: List<HostingPlan>,
    val minPrice: Long,
    val ramLow: Int,
    val ramHigh: Int,
    val playersMax: Int,
) {
    val paidAwake: Boolean get() = paid.isNotEmpty() && paid.none { it.sleeps }
}

internal fun hostingPlanFacts(plans: List<HostingPlan>): HostingPlanFacts {
    val paid = plans.filter { !it.free }
    val prices = paid.map { it.priceKopecks }.filter { it > 0 }
    val rams = paid.map { it.ramMb }.filter { it > 0 }
    val players = paid.map { if (it.playersComfort > 0) it.playersComfort else it.maxPlayers }.filter { it > 0 }
    return HostingPlanFacts(
        free = plans.firstOrNull { it.free },
        paid = paid,
        minPrice = prices.minOrNull() ?: 0,
        ramLow = rams.minOrNull() ?: 0,
        ramHigh = rams.maxOrNull() ?: 0,
        playersMax = players.maxOrNull() ?: 0,
    )
}

private data class HostingRoadFact(val icon: MillidaIcon, val value: String, val caption: String)

@Composable
fun HostingRoads(
    panel: HostingPanel,
    onPaid: () -> Unit,
    onFree: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val facts = remember(panel.plans) { hostingPlanFacts(panel.plans) }
    val loading = panel.plans.isEmpty() && panel.plansLoading
    if (LocalWideLayout.current) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaidRoad(facts, loading, onPaid, Modifier.weight(WIDE_PAID_ROAD))
            FreeRoad(facts, loading, onFree, Modifier.weight(1f), factsPerRow = 1)
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaidRoad(facts, loading, onPaid)
            FreeRoad(facts, loading, onFree)
        }
    }
}

/** The desktop's two roads side by side, `.hsx-roads` 1.2fr : 1fr. */
private const val WIDE_PAID_ROAD = 1.2f

@Composable
private fun PaidRoad(facts: HostingPlanFacts, loading: Boolean, onPaid: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    val tone = MHeroTone.server(colors.accent)
    val shadow = with(LocalDensity.current) { 2.dp.toPx() }
    val ramText = when {
        facts.ramHigh <= 0 -> null
        facts.ramLow > 0 && facts.ramLow != facts.ramHigh -> "${hostingGb(facts.ramLow)}–${hostingGb(facts.ramHigh)} ГБ"
        else -> "${hostingGb(facts.ramHigh)} ГБ"
    }
    val list = buildList {
        if (ramText != null) add(HostingRoadFact(MIcons.monitor, ramText, "памяти"))
        if (facts.playersMax > 0) add(HostingRoadFact(MIcons.users, "до ${facts.playersMax}", "игроков"))
        add(HostingRoadFact(MIcons.blocks, "Моды", "и плагины"))
        if (facts.paidAwake) add(HostingRoadFact(MIcons.clock, "24/7", "не засыпает"))
    }
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(
                fill = tone.plate,
                cut = MillidaCut.plate,
                frame = PixelFrame(
                    outline = tone.outline,
                    outlineWidth = 3.dp,
                    top = MillidaPixel.tileTop,
                    topWidth = 4.dp,
                    bottom = MillidaPixel.tileBottom,
                    bottomWidth = 5.dp,
                ),
                dots = true,
            )
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "Платный сервер",
                modifier = Modifier.weight(1f),
                style = MillidaType.tileTitle.copy(shadow = Shadow(MillidaPixel.textShadow, Offset(0f, shadow), 0f)),
                color = MillidaPixel.white,
            )
            if (facts.minPrice > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("от ${hostingRubles(facts.minPrice)}", style = MillidaType.pixel, color = MillidaPixel.white, maxLines = 1)
                    Text("в месяц", style = MillidaType.small, color = MillidaPixel.whiteSoft, maxLines = 1)
                }
            }
        }
        RoadFacts(list, loading, tone.band, MillidaPixel.white, MillidaPixel.whiteSoft)
        MPrimaryButton(
            "Выбрать тариф",
            onClick = onPaid,
            modifier = Modifier.fillMaxWidth(),
            icon = MIcons.server,
            size = MButtonSize.Large,
        )
    }
}

@Composable
private fun FreeRoad(
    facts: HostingPlanFacts,
    loading: Boolean,
    onFree: () -> Unit,
    modifier: Modifier = Modifier,
    factsPerRow: Int = 2,
) {
    val colors = millida
    val free = facts.free
    val list = buildList {
        if (free != null && free.ramMb > 0) add(HostingRoadFact(MIcons.monitor, "${hostingGb(free.ramMb)} ГБ", "памяти"))
        if (free != null && free.maxPlayers > 0) add(HostingRoadFact(MIcons.users, "${free.maxPlayers}", "друзей"))
        if (free != null && free.worldKeepDays > 0) add(HostingRoadFact(MIcons.clock, hostingDaysLabel(free.worldKeepDays), "хранится мир"))
    }
    MCard(modifier = modifier, contentPadding = 18.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "Бесплатно",
                modifier = Modifier.weight(1f),
                style = MillidaType.tileTitle.copy(fontSize = 19.sp),
                color = colors.fg,
            )
            Text("0 ₽", style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
        }
        if (loading || list.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            RoadFacts(list, loading, colors.surface2, colors.fg, colors.fgSubtle, factsPerRow)
        }
        Spacer(Modifier.height(14.dp))
        MSecondaryButton(
            "Начать бесплатно",
            onClick = onFree,
            modifier = Modifier.fillMaxWidth(),
            icon = MIcons.play,
            size = MButtonSize.Large,
        )
    }
}

@Composable
private fun RoadFacts(
    list: List<HostingRoadFact>,
    loading: Boolean,
    fill: Color,
    ink: Color,
    sub: Color,
    perRow: Int = 2,
) {
    val rows = if (loading) List(2) { List<HostingRoadFact?>(perRow) { null } } else list.chunked(perRow)
    val phase = rememberShimmer()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { fact ->
                    if (fact == null) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .pixelSurface(fill, MillidaCut.control)
                                .shimmer(phase, sub.copy(alpha = 0.12f)),
                        )
                    } else {
                        HostingFact(
                            icon = fact.icon,
                            value = fact.value,
                            caption = fact.caption,
                            modifier = Modifier.weight(1f),
                            fill = fill,
                            ink = ink,
                            sub = sub,
                        )
                    }
                }
                repeat(perRow - pair.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun hostingDaysLabel(days: Int): String {
    val tail = days % 100
    val last = days % 10
    val word = when {
        tail in 11..14 -> "дней"
        last == 1 -> "день"
        last in 2..4 -> "дня"
        else -> "дней"
    }
    return "$days $word"
}
