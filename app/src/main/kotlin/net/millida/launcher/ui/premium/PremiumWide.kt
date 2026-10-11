package net.millida.launcher.ui.premium

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumAction
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.premium.PremiumSubscription
import net.millida.launcher.core.premium.PremiumTier
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MScreen
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionHeader
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.WIDE_ROOMY_HEIGHT_DP
import net.millida.launcher.ui.catalog.PhoneOnlyNote
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.settings.BugSheet

private val HERO_HEIGHT = 320.dp
private val HERO_HEIGHT_LOW = 220.dp
private val SKELETON_CARD_HEIGHT = 176.dp
private val SHOT_WIDTH = 214.dp
private val TILE_MIN_WIDTH = 130.dp
private val PLAN_MIN_WIDTH = 240.dp
private const val SKELETON_CARDS = 3

/**
 * The desktop lays the name plate over the art; a see-through surface keeps
 * the text readable in either theme where the desktop's fixed dark plate
 * would not.
 */
private const val ART_PLATE_ALPHA = 0.86f

internal val PREMIUM_CARD_MIN_WIDTH = 240.dp

internal const val PREMIUM_TITLE = "Премиум"

@Composable
private fun heroHeight(): Dp =
    if (LocalConfiguration.current.screenHeightDp >= WIDE_ROOMY_HEIGHT_DP) HERO_HEIGHT else HERO_HEIGHT_LOW

/**
 * The desktop showcase hero (`PremiumHero.tsx`): the art across the page and
 * a plate on its lower edge with the name, the badges, one line of facts and
 * the main button, «Подробнее» and the price in one row. On the pack's own
 * page [onOpen] is null and «Подробнее» is not there.
 */
@Composable
internal fun WidePremiumHero(
    pack: PremiumPack,
    plan: PremiumTier?,
    sub: PremiumSubscription?,
    waiting: Boolean,
    asking: Boolean,
    onPrimary: () -> Unit,
    onOpen: (() -> Unit)?,
    art: String? = pack.coverUrl,
) {
    val colors = millida
    val line = pack.tagline ?: Premium.facts(pack).ifEmpty { null }
    val details: (@Composable () -> Unit)? = if (onOpen != null) {
        { MSecondaryButton("Подробнее", onClick = onOpen) }
    } else {
        null
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(heroHeight())
            .pixelSurface(colors.surface2, MillidaCut.plate),
    ) {
        MRemoteImage(art, MIcons.crown, Modifier.fillMaxSize(), shape = RectangleShape)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(colors.surface.copy(alpha = ART_PLATE_ALPHA))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pack.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MillidaType.screenTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                PackBadge(pack)
                pack.ageRating?.let {
                    Spacer(Modifier.width(8.dp))
                    MBadge(it, colors.fgMuted, colors.surface2)
                }
            }
            if (line != null) {
                Text(line, style = MillidaType.body, color = colors.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BuyLine(pack, plan, sub, waiting, asking, onPrimary, beside = details)
        }
    }
}

/**
 * A card of the desktop feed (`PremiumCard.tsx`): the art at 16:9 with the
 * badge in its corner and the name on its lower edge, then the facts with
 * the price at the right end.
 */
@Composable
internal fun WidePremiumCard(
    pack: PremiumPack,
    plan: PremiumTier?,
    sub: PremiumSubscription?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val price = if (Premium.hasAccess(pack, sub)) "Открыто" else Premium.priceLabel(pack, plan)
    val downloads = pack.downloads ?: 0
    MCard(modifier = modifier, onClick = onOpen, contentPadding = 0.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(colors.surface2),
        ) {
            MRemoteImage(pack.coverUrl, MIcons.crown, Modifier.fillMaxSize(), shape = RectangleShape)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                PackBadge(pack, small = true)
            }
            Text(
                pack.title,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = ART_PLATE_ALPHA))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Premium.facts(pack),
                modifier = Modifier.weight(1f),
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (price.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(price, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
            } else if (downloads > 0) {
                Spacer(Modifier.width(8.dp))
                MIcon(MIcons.download, size = 13.dp, color = colors.fgFaint)
                Spacer(Modifier.width(4.dp))
                Text(Premium.count(downloads), style = MillidaType.small, color = colors.fgMuted, maxLines = 1)
            }
        }
    }
}

/** The desktop's `.pm-skel`: the hero's block and a row of three cards. */
@Composable
internal fun WidePremiumSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MSkeleton(height = heroHeight())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(SKELETON_CARDS) {
                MSkeleton(height = SKELETON_CARD_HEIGHT, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * A pack's page inside the showcase, as the desktop opens it (`PremiumPage.tsx`)
 * in place of the feed: «Назад», the hero without «Подробнее», the frames in
 * a strip, the numbers as tiles, what comes with it, the plans, the patch
 * notes and the price block again at the bottom of a long page. Leaving the
 * screen closes the page so the lobby does not show it as a sheet.
 */
@Composable
internal fun WidePackPage(model: LauncherViewModel, page: PackPageState, modifier: Modifier = Modifier) {
    val premium by model.premium.state.collectAsStateWithLifecycle()
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
    val video = page.video
    val patches = detail?.patches.orEmpty()
    val long = shots.isNotEmpty() || patches.isNotEmpty()
    val waiting = pack.id in premium.waiting
    val asking = premium.asking == pack.id
    val buy = { model.premium.primary(pack, plan, sub) }
    val close = { model.premium.closePage() }

    BackHandler(onBack = close)
    MScreen(
        modifier = modifier,
        title = if (LocalConfiguration.current.screenHeightDp >= WIDE_ROOMY_HEIGHT_DP) null else PREMIUM_TITLE,
        onBack = {
            model.premium.closePage()
            model.back()
        },
        spacing = 14.dp,
    ) {
        item(key = "page-back") {
            Row {
                MSecondaryButton("Назад", onClick = close, icon = MIcons.chevronLeft, size = MButtonSize.Small)
            }
        }
        item(key = "page-hero") {
            WidePremiumHero(pack, plan, sub, waiting, asking, onPrimary = buy, onOpen = null, art = page.art)
        }
        page.health?.let { health ->
            item(key = "page-health") { HealthRow(health, onReport = { reporting = true }) }
        }
        if (video != null || shots.isNotEmpty()) {
            item(key = "page-media") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (video != null) {
                        MCard(
                            modifier = Modifier.width(SHOT_WIDTH),
                            onClick = { model.premium.openVideo(video) },
                            background = colors.surface2,
                            contentPadding = 0.dp,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f),
                                contentAlignment = Alignment.Center,
                            ) {
                                MIcon(MIcons.play, size = 30.dp, color = colors.fgMuted)
                            }
                        }
                    }
                    shots.forEach { shot ->
                        MRemoteImage(
                            shot,
                            MIcons.image,
                            Modifier
                                .width(SHOT_WIDTH)
                                .aspectRatio(16f / 9f),
                            shape = MillidaShape.control,
                        )
                    }
                }
            }
        }
        if (tiles.isNotEmpty()) {
            item(key = "page-tiles") {
                FitGrid(tiles, TILE_MIN_WIDTH, gap = 10.dp) { tile, cell ->
                    MCard(modifier = cell, contentPadding = 10.dp) {
                        Text(tile.label, style = MillidaType.small, color = colors.fgFaint, maxLines = 1)
                        Text(tile.value, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (includes.isNotEmpty()) {
            item(key = "page-includes") {
                Column {
                    MSectionHeader("Что входит")
                    Checks(includes)
                }
            }
        }
        if (plans.isNotEmpty()) {
            item(key = "page-plans") {
                Column {
                    MSectionHeader("Подписка")
                    FitGrid(plans, PLAN_MIN_WIDTH, gap = 10.dp) { tier, cell ->
                        MCard(modifier = cell, contentPadding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tier.title, modifier = Modifier.weight(1f), style = MillidaType.rowTitle, color = colors.fg, maxLines = 1)
                                Text(Premium.planPrice(tier), style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
                            }
                            if (tier.items.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Checks(tier.items)
                            }
                        }
                    }
                }
            }
        }
        if (patches.isNotEmpty()) {
            item(key = "page-patches") {
                Column {
                    MSectionHeader("Обновления")
                    patches.forEach { patch ->
                        val day = Premium.untilText(patch.date)
                        Row(Modifier.padding(vertical = 3.dp)) {
                            if (day.isNotEmpty()) {
                                Text(day, style = MillidaType.small, color = colors.fgFaint, modifier = Modifier.width(92.dp))
                            }
                            Text(patch.text, style = MillidaType.body, color = colors.fgMuted)
                        }
                    }
                }
            }
        }
        if (long || includes.isNotEmpty() || plans.isNotEmpty()) {
            item(key = "page-buy") {
                WideBuyBlock(pack, plan, sub, waiting, asking, buy)
            }
        }
        item(key = "page-cancel") {
            Column { CancelRow(sub, onManage = model.premium::manage) }
        }
        if (detail == null && page.view == null && (page.loading || page.failed)) {
            item(key = "page-state") {
                if (page.loading) {
                    MSkeletonRow()
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MIcon(MIcons.alert, size = 18.dp, color = colors.warning)
                        Spacer(Modifier.width(8.dp))
                        Text("Страница не догрузилась", modifier = Modifier.weight(1f), style = MillidaType.small, color = colors.fgMuted)
                        MTextButton("Повторить", onClick = { model.premium.retryPage() }, icon = MIcons.refresh)
                    }
                }
            }
        }
        item(key = "page-legal") {
            Text(
                "Доступ к сборке: моды скачиваются при установке. Не продукт Mojang." + (pack.ageRating?.let { " $it" } ?: ""),
                style = MillidaType.small,
                color = colors.fgFaint,
            )
        }
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

/**
 * The desktop's `.pm-buy` at the foot of a long page: the name with the price
 * under it on the left, the main button and the plan's name on the right.
 */
@Composable
private fun WideBuyBlock(
    pack: PremiumPack,
    plan: PremiumTier?,
    sub: PremiumSubscription?,
    waiting: Boolean,
    asking: Boolean,
    onPrimary: () -> Unit,
) {
    val colors = millida
    val action = Premium.action(pack, plan, sub)
    val price = Premium.priceLine(pack, plan, sub)
    MCard(contentPadding = 14.dp) {
        if (action is PremiumAction.PcOnly) {
            Text(pack.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            PhoneOnlyNote(action.reason)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pack.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (price != null) PriceText(price)
                }
                Spacer(Modifier.width(16.dp))
                BuyButton(action, waiting, asking, onPrimary)
                if (plan != null && pack.inSubscription && !Premium.hasAccess(pack, sub)) {
                    Spacer(Modifier.width(12.dp))
                    Text(plan.title, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The desktop's `repeat(auto-fit, minmax(…))`: as many cells of at least
 * [min] as fit in a row, and fewer items stretch to fill it.
 */
@Composable
private fun <T> FitGrid(
    items: List<T>,
    min: Dp,
    gap: Dp,
    cell: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = ((maxWidth + gap) / (min + gap)).toInt().coerceAtLeast(1)
        val columns = fit.coerceAtMost(items.size).coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { cell(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
