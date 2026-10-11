package net.millida.launcher.ui.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.premium.PackSource
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.premium.PremiumSubscription
import net.millida.launcher.core.premium.PremiumTier
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MScreen
import net.millida.launcher.ui.MSectionHeader
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.adaptiveColumns
import net.millida.launcher.ui.adaptiveItems
import net.millida.launcher.ui.home.PairRows
import net.millida.launcher.ui.millida

private const val WIDE_CARD_COLUMNS = 5

/**
 * The paid-pack showcase: the hero pack with its button and price, the cancel
 * line, and the other packs. Every card, price and number comes from the
 * server; what the answer lacks is not on the screen.
 */
@Composable
fun PremiumScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val premium by model.premium.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.premium.ensureShowcase() }
    val showcase = premium.showcase
    val plan = premium.plan
    val sub = premium.subscription
    val featured = showcase?.featured
    val rest = showcase?.rest.orEmpty()
    val wide = LocalWideLayout.current
    val columns = adaptiveColumns(cell = PREMIUM_CARD_MIN_WIDTH, max = WIDE_CARD_COLUMNS)
    val listState = rememberLazyListState()
    val page = premium.page

    if (wide && page != null) {
        WidePackPage(model, page, modifier)
        MillidaShelfSheets(state, model)
        return
    }

    MScreen(modifier = modifier, title = PREMIUM_TITLE, onBack = { model.back() }, listState = listState, spacing = 14.dp) {
        when {
            showcase == null && premium.error != null -> item(key = "premium-error") {
                MErrorCard(premium.error ?: "", onRetry = model.premium::reload)
            }
            showcase == null && wide -> item(key = "premium-wait") { WidePremiumSkeleton() }
            showcase == null -> item(key = "premium-wait") {
                Column {
                    MSkeleton(height = 260.dp)
                    Spacer(Modifier.height(14.dp))
                    Row {
                        MSkeleton(height = 180.dp, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        MSkeleton(height = 180.dp, modifier = Modifier.weight(1f))
                    }
                }
            }
            featured == null -> item(key = "premium-soon") {
                MEmptyState(
                    icon = MIcons.crown,
                    title = "Платные сборки скоро",
                    actionText = "Обновить",
                    onAction = model.premium::reload,
                    actionIcon = MIcons.refresh,
                    primary = false,
                )
            }
            wide -> {
                item(key = "premium-hero") {
                    WidePremiumHero(
                        pack = featured,
                        plan = plan,
                        sub = sub,
                        waiting = featured.id in premium.waiting,
                        asking = premium.asking == featured.id,
                        onPrimary = { model.premium.primary(featured, plan, sub) },
                        onOpen = { model.premium.open(featured) },
                    )
                }
                item(key = "premium-cancel") { Column { CancelRow(sub, onManage = model.premium::manage) } }
                if (rest.isNotEmpty()) {
                    item(key = "premium-rest-head") { MSectionHeader("Другие сборки") }
                    adaptiveItems(rest, columns, key = { it.id }, contentType = "premium-card", gap = 12.dp) { pack ->
                        WidePremiumCard(pack, plan, sub, onOpen = { model.premium.open(pack) })
                    }
                }
            }
            else -> {
                item(key = "premium-hero") {
                    PremiumHero(
                        pack = featured,
                        plan = plan,
                        sub = sub,
                        waiting = featured.id in premium.waiting,
                        asking = premium.asking == featured.id,
                        onPrimary = { model.premium.primary(featured, plan, sub) },
                        onOpen = { model.premium.open(featured) },
                    )
                }
                item(key = "premium-cancel") { CancelRow(sub, onManage = model.premium::manage) }
                if (rest.isNotEmpty()) {
                    item(key = "premium-rest") {
                        Column {
                            MSectionHeader("Другие сборки")
                            PairRows(rest) { pack, cell ->
                                PremiumCard(pack, plan, sub, onOpen = { model.premium.open(pack) }, modifier = cell)
                            }
                        }
                    }
                }
            }
        }
    }

    PackPageSheet(model)
    MillidaShelfSheets(state, model)
}

@Composable
private fun PremiumHero(
    pack: PremiumPack,
    plan: PremiumTier?,
    sub: PremiumSubscription?,
    waiting: Boolean,
    asking: Boolean,
    onPrimary: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = millida
    val line = pack.tagline ?: Premium.facts(pack).ifEmpty { null }
    MCard(onClick = onOpen, contentPadding = 12.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(MillidaShape.control)
                .background(colors.surface2),
        ) {
            MRemoteImage(pack.coverUrl, MIcons.crown, Modifier.fillMaxSize(), shape = MillidaShape.control)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pack.title,
                modifier = Modifier.weight(1f, fill = false),
                style = MillidaType.dialogTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            PackBadge(pack)
            pack.ageRating?.let {
                Spacer(Modifier.width(6.dp))
                MBadge(it, colors.fgMuted, colors.surface2)
            }
        }
        if (line != null) {
            Spacer(Modifier.height(6.dp))
            Text(line, style = MillidaType.body, color = colors.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        BuyRow(pack, plan, sub, waiting, asking, onPrimary)
        Spacer(Modifier.height(8.dp))
        MSecondaryButton("Подробнее", onClick = onOpen, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun PackBadge(pack: PremiumPack, small: Boolean = false) {
    val colors = millida
    when {
        pack.inSubscription -> MBadge(if (small) "Подписка" else "По подписке", colors.warning, colors.warningSoft, icon = MIcons.crown)
        pack.source == PackSource.CATALOG -> MBadge("Платная", colors.warning, colors.warningSoft, icon = MIcons.crown)
    }
}

/** A card of the feed: art, the subscription badge, the name, the facts and the price. */
@Composable
private fun PremiumCard(
    pack: PremiumPack,
    plan: PremiumTier?,
    sub: PremiumSubscription?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val price = if (Premium.hasAccess(pack, sub)) "Открыто" else Premium.priceLabel(pack, plan)
    MCard(modifier = modifier, onClick = onOpen, contentPadding = 10.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(MillidaShape.control)
                .background(colors.surface2),
        ) {
            MRemoteImage(pack.coverUrl, MIcons.crown, Modifier.fillMaxSize(), shape = MillidaShape.control)
            Box(Modifier.padding(6.dp)) { PackBadge(pack, small = true) }
        }
        Spacer(Modifier.height(8.dp))
        Text(pack.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Premium.facts(pack), style = MillidaType.small, color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            price.isNotEmpty() -> Text(price, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
            (pack.downloads ?: 0) > 0 -> Text(
                "${Premium.count(pack.downloads ?: 0)} скачиваний",
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 1,
            )
        }
    }
}
