package net.millida.launcher.ui.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.hub.HealthLine
import net.millida.launcher.core.hub.HealthTone
import net.millida.launcher.core.premium.PackSource
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumAction
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.premium.PremiumSubscription
import net.millida.launcher.core.premium.PremiumTier
import net.millida.launcher.core.premium.PriceLine
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.catalog.PhoneOnlyNote
import net.millida.launcher.ui.home.PairRows
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.settings.BugSheet

/** Frames above this count are told by a counter: more dots than ten cannot be told apart. */
private const val DOTS_LIMIT = 10

/**
 * A pack's page as a sheet: art, the main button with the price beside it,
 * the pack's health, frames, live numbers, what comes with it, the plans and
 * the patch notes; the price block repeats at the bottom of a long page.
 */
@Composable
internal fun PackPageSheet(model: LauncherViewModel) {
    val premium by model.premium.state.collectAsStateWithLifecycle()
    val page = premium.page ?: return
    val support by model.support.state.collectAsStateWithLifecycle()
    var reporting by rememberSaveable(page.pack.id) { mutableStateOf(false) }
    val colors = millida
    val pack = page.full
    val plan = model.premium.planOf(page)
    val sub = model.premium.subscriptionOf(page)
    val detail = page.detail
    val plans = detail?.let { Premium.plans(it, premium.plan) }.orEmpty()
    val includes = detail?.let { Premium.includes(it) } ?: if (pack.hasServer) listOf("Сервер сборки") else emptyList()
    val tiles = Premium.tiles(pack, detail?.updatedAt)
    val shots = page.shots
    val patches = detail?.patches.orEmpty()
    val long = shots.isNotEmpty() || patches.isNotEmpty()
    val waiting = pack.id in premium.waiting
    val asking = premium.asking == pack.id

    MBottomSheet(onDismiss = model.premium::closePage) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(MillidaShape.control)
                .background(colors.surface2),
        ) {
            MRemoteImage(page.art, MIcons.crown, Modifier.fillMaxSize(), shape = MillidaShape.control)
        }
        Spacer(Modifier.height(14.dp))
        Text(pack.title, style = MillidaType.dialogTitle, color = colors.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                pack.inSubscription -> MBadge("По подписке", colors.warning, colors.warningSoft, icon = MIcons.crown)
                pack.source == PackSource.CATALOG && pack.accessRequired -> MBadge("Платная", colors.warning, colors.warningSoft, icon = MIcons.crown)
            }
            pack.ageRating?.let { MBadge(it, colors.fgMuted, colors.surface2) }
        }
        val line = pack.tagline ?: Premium.facts(pack).ifEmpty { null }
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            Text(line, style = MillidaType.body, color = colors.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        BuyRow(pack, plan, sub, waiting, asking) { model.premium.primary(pack, plan, sub) }
        page.health?.let { health ->
            Spacer(Modifier.height(10.dp))
            HealthRow(health, onReport = { reporting = true })
        }

        if (page.video != null || shots.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Frames(shots, page.video, onVideo = model.premium::openVideo)
        }
        if (tiles.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            PairRows(tiles, gap = 8.dp) { tile, modifier ->
                MCard(modifier = modifier, background = colors.surface2, contentPadding = 10.dp) {
                    Text(tile.label, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
                    Text(tile.value, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (includes.isNotEmpty()) {
            Block("ЧТО ВХОДИТ") { Checks(includes) }
        }
        if (plans.isNotEmpty()) {
            Block("ПОДПИСКА") {
                plans.forEach { tier ->
                    MCard(background = colors.surface2, contentPadding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tier.title, modifier = Modifier.weight(1f), style = MillidaType.rowTitle, color = colors.fg, maxLines = 1)
                            Text(Premium.planPrice(tier), style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
                        }
                        if (tier.items.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Checks(tier.items)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (patches.isNotEmpty()) {
            Block("ОБНОВЛЕНИЯ") {
                patches.forEach { patch ->
                    val day = Premium.untilText(patch.date)
                    Row(Modifier.padding(vertical = 4.dp)) {
                        if (day.isNotEmpty()) {
                            Text(day, style = MillidaType.small, color = colors.fgSubtle, modifier = Modifier.width(88.dp))
                        }
                        Text(patch.text, style = MillidaType.body, color = colors.fgMuted)
                    }
                }
            }
        }
        if (long || includes.isNotEmpty() || plans.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MCard(background = colors.surface2, contentPadding = 12.dp) {
                Text(pack.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                BuyRow(pack, plan, sub, waiting, asking) { model.premium.primary(pack, plan, sub) }
            }
        }
        CancelRow(sub, onManage = model.premium::manage)
        when {
            page.loading && detail == null && page.view == null -> {
                Spacer(Modifier.height(12.dp))
                MSkeletonRow()
            }
            page.failed && detail == null && page.view == null -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.alert, size = 18.dp, color = colors.warning)
                    Spacer(Modifier.width(8.dp))
                    Text("Страница не догрузилась", modifier = Modifier.weight(1f), style = MillidaType.small, color = colors.fgMuted)
                    MTextButton("Повторить", onClick = { model.premium.retryPage() }, icon = MIcons.refresh)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Доступ к сборке: моды скачиваются при установке. Не продукт Mojang." + (pack.ageRating?.let { " $it" } ?: ""),
            style = MillidaType.small,
            color = colors.fgFaint,
        )
    }

    if (reporting) {
        BugSheet(
            sending = support.sendingBug,
            onSend = { kind, text, attach, _ ->
                model.support.sendBug(kind, "Сборка «${pack.title}» (${pack.slug ?: pack.id}): $text", attach) { reporting = false }
            },
            onDismiss = { reporting = false },
        )
    }
}

/** The main button and the price beside it; one primary per zone. */
@Composable
internal fun BuyRow(
    pack: PremiumPack,
    plan: PremiumTier?,
    sub: PremiumSubscription?,
    waiting: Boolean,
    asking: Boolean,
    onPrimary: () -> Unit,
) {
    BuyLine(pack, plan, sub, waiting, asking, onPrimary, beside = null)
}

/**
 * [BuyRow] with a control between the main button and the price, where the
 * desktop hero keeps «Подробнее».
 */
@Composable
internal fun BuyLine(
    pack: PremiumPack,
    plan: PremiumTier?,
    sub: PremiumSubscription?,
    waiting: Boolean,
    asking: Boolean,
    onPrimary: () -> Unit,
    beside: (@Composable () -> Unit)?,
) {
    val action = Premium.action(pack, plan, sub)
    if (action is PremiumAction.PcOnly) {
        PhoneOnlyNote(action.reason)
        if (beside != null) {
            Spacer(Modifier.height(8.dp))
            beside()
        }
        return
    }
    val price = Premium.priceLine(pack, plan, sub)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BuyButton(action, waiting, asking, onPrimary)
        if (beside != null) {
            Spacer(Modifier.width(10.dp))
            beside()
        }
        if (price != null) {
            Spacer(Modifier.width(12.dp))
            PriceText(price, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun BuyButton(action: PremiumAction, waiting: Boolean, asking: Boolean, onPrimary: () -> Unit) {
    when {
        waiting && (action is PremiumAction.Subscribe || action is PremiumAction.Buy) -> MButton(
            text = "Ждём оплату",
            onClick = {},
            variant = MButtonVariant.Running,
            icon = MIcons.clock,
            enabled = false,
        )
        action is PremiumAction.Install -> MPrimaryButton("Установить", onClick = onPrimary, icon = MIcons.download)
        action is PremiumAction.Soon -> MPrimaryButton("Скоро", onClick = {}, icon = MIcons.download, enabled = false)
        action is PremiumAction.Subscribe -> MPrimaryButton("Оформить", onClick = onPrimary, icon = MIcons.crown, loading = asking)
        action is PremiumAction.Buy -> MPrimaryButton("Купить", onClick = onPrimary, icon = MIcons.gem, loading = asking)
        else -> Unit
    }
}

@Composable
internal fun PriceText(price: PriceLine, modifier: Modifier = Modifier) {
    val colors = millida
    Column(modifier) {
        Text(price.main, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1)
        price.notes.forEach { note ->
            Text(note, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Cancelling is never hidden: the note and the link sit where the subscription is. */
@Composable
internal fun CancelRow(sub: PremiumSubscription?, onManage: (PremiumSubscription) -> Unit) {
    val note = Premium.cancelNote(sub) ?: return
    val colors = millida
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        MIcon(MIcons.info, size = 16.dp, color = colors.fgSubtle)
        Spacer(Modifier.width(8.dp))
        Text(note, modifier = Modifier.weight(1f), style = MillidaType.small, color = colors.fgSubtle)
        if (sub != null && sub.manageUrl != null) {
            MTextButton(if (sub.active) "Отменить" else "Подписка", onClick = { onManage(sub) })
        }
    }
}

@Composable
internal fun HealthRow(health: HealthLine, onReport: () -> Unit) {
    val colors = millida
    val tone = if (health.tone == HealthTone.WARN) colors.warning else colors.accent
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(tone),
            )
            Spacer(Modifier.width(8.dp))
            Text(health.head, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1)
            if (health.whenText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(health.whenText, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
            }
        }
        health.pct?.let { pct ->
            Text("$pct% запусков без ошибок", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        }
        if (health.report) MTextButton("Сообщить о проблеме", onClick = onReport, icon = MIcons.alert)
    }
}

/** The pack's frames: a hand-driven carousel at 16:9 with dots, or a counter past ten; the video opens outside. */
@Composable
private fun Frames(shots: List<String>, video: String?, onVideo: (String) -> Unit) {
    val colors = millida
    if (video != null) {
        MButton(
            text = "Видео",
            onClick = { onVideo(video) },
            modifier = Modifier.fillMaxWidth(),
            variant = MButtonVariant.Secondary,
            size = MButtonSize.Medium,
            icon = MIcons.play,
        )
        if (shots.isNotEmpty()) Spacer(Modifier.height(10.dp))
    }
    if (shots.isEmpty()) return
    val pager = rememberPagerState(pageCount = { shots.size })
    HorizontalPager(
        state = pager,
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(MillidaShape.control),
        pageSpacing = 8.dp,
    ) { index ->
        MRemoteImage(shots[index], MIcons.image, Modifier.fillMaxSize(), shape = MillidaShape.control)
    }
    if (shots.size > 1) {
        Spacer(Modifier.height(8.dp))
        if (shots.size > DOTS_LIMIT) {
            Text(
                "${pager.currentPage + 1} / ${shots.size}",
                modifier = Modifier.fillMaxWidth(),
                style = MillidaType.pixelSmall,
                color = colors.fgSubtle,
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                repeat(shots.size) { index ->
                    Box(
                        Modifier
                            .size(if (index == pager.currentPage) 10.dp else 7.dp)
                            .background(if (index == pager.currentPage) colors.accent else colors.borderStrong),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Block(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    MSectionTitle(title)
    Spacer(Modifier.height(8.dp))
    content()
}

@Composable
internal fun Checks(lines: List<String>) {
    val colors = millida
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.check, size = 16.dp, color = colors.accentText)
                Spacer(Modifier.width(8.dp))
                Text(line, style = MillidaType.body, color = colors.fgMuted)
            }
        }
    }
}
