package net.millida.launcher.ui.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.NewBuild
import net.millida.launcher.core.catalog.MillidaKind
import net.millida.launcher.core.catalog.MillidaPaid
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSectionHeader
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

/** The Millida block of a content tab; for maps it is the whole tab. */
internal fun LazyListScope.millidaContentShelf(
    content: ContentShelfState,
    shelf: ShelfState,
    icon: MillidaIcon,
    foreignBelow: Boolean,
    onInstall: (MillidaCard) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onPurchases: () -> Unit,
    onFilters: (MillidaFilters) -> Unit,
    onShowAll: () -> Unit,
    onNewBuild: (MillidaCard) -> Unit,
) {
    val kind = content.kind ?: return
    item(key = "millida-content-head") {
        MSectionHeader(title = "Каталог Millida", action = "Мои покупки", onAction = onPurchases)
    }
    item(key = "millida-content-filters") {
        MillidaFilterBar(
            filters = content.filters,
            facets = content.facets,
            total = content.total.takeIf { content.page > 0 && !content.loading },
            onChange = onFilters,
        )
    }
    when {
        content.items.isEmpty() && content.loading -> items(3, key = { "millida-content-skeleton-$it" }) { MSkeletonRow() }
        content.items.isEmpty() && content.error != null -> item(key = "millida-content-error") {
            MErrorCard(message = content.error, onRetry = onRetry)
        }
        content.items.isEmpty() -> item(key = "millida-content-empty") {
            CatalogEmpty(searching = content.query.isNotBlank() || content.filters.active, onShowAll = onShowAll)
        }
    }
    items(content.items, key = { "millida-content:${it.slug}" }, contentType = { "millida-content-card" }) { card ->
        MillidaContentCard(
            card = card,
            kind = kind,
            icon = icon,
            bought = shelf.open(card),
            locked = shelf.locked(card),
            waiting = card.slug in shelf.waiting,
            installed = card.slug in content.installed,
            job = content.jobs[card.slug],
            running = card.slug in content.jobs,
            onInstall = { onInstall(card) },
            onNewBuild = if (NewBuild.plan(kind.id, card.versions, card.loaders) != null) ({ onNewBuild(card) }) else null,
        )
    }
    when {
        content.loadingMore -> item(key = "millida-content-more-loading") { MSkeletonRow() }
        content.more -> item(key = "millida-content-more") {
            MTextButton(text = "Показать ещё", onClick = onMore, modifier = Modifier.fillMaxWidth(), color = millida.accentText)
        }
    }
    if (foreignBelow) item(key = "millida-content-foreign-head") { MSectionHeader(title = "Modrinth и CurseForge") }
}

@Composable
private fun MillidaContentCard(
    card: MillidaCard,
    kind: MillidaKind,
    icon: MillidaIcon,
    bought: Boolean,
    locked: Boolean,
    waiting: Boolean,
    installed: Boolean,
    job: Float?,
    running: Boolean,
    onInstall: () -> Unit,
    onNewBuild: (() -> Unit)?,
) {
    val colors = millida
    MCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            MRemoteImage(url = card.icon ?: card.cover, icon = icon, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.author.isNotBlank()) {
                    Text("от ${card.author}", style = MillidaType.small, color = colors.fgFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (card.summary.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(card.summary, style = MillidaType.small, color = colors.fgSubtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (card.downloads > 0) DownloadsFact(card.downloads)
                when {
                    installed -> MBadge("Установлено", colors.accentText, colors.accentSoft, icon = MIcons.check)
                    card.paid && bought -> MBadge("Куплено", colors.accentText, colors.accentSoft, icon = MIcons.check)
                    card.paid -> MBadge(MillidaPaid.priceTag(card.pricing, card.priceKopecks), colors.info, colors.infoSoft)
                }
                card.versions.firstOrNull()?.let { MBadge(it, colors.fgMuted, colors.surface2) }
            }
            Spacer(Modifier.width(8.dp))
            when {
                running -> MButton(text = job?.let { "${(it * 100).toInt()}%" } ?: "Ставим", onClick = {}, size = MButtonSize.Small, loading = true)
                installed -> Unit
                locked -> MPrimaryButton(
                    text = if (waiting) "Ждём оплату" else MillidaPaid.buyLabel(card.pricing),
                    onClick = onInstall,
                    enabled = !waiting,
                    icon = MIcons.wallet,
                    size = MButtonSize.Small,
                )
                kind == MillidaKind.WORLD -> MPrimaryButton(
                    text = "Установить",
                    onClick = onInstall,
                    icon = MIcons.download,
                    size = MButtonSize.Small,
                )
                else -> MPrimaryButton(
                    text = "В сборку",
                    onClick = onInstall,
                    icon = MIcons.plus,
                    size = MButtonSize.Small,
                )
            }
        }
        if (onNewBuild != null && !running && !installed && !locked) {
            Spacer(Modifier.height(8.dp))
            MSecondaryButton(text = "Новая сборка", onClick = onNewBuild, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small)
        }
        if (running) {
            Spacer(Modifier.height(10.dp))
            MProgressBar(progress = job)
        }
    }
}

/** A link's confirmation, or the question a file made for another version asks. */
@Composable
internal fun ContentAskDialog(ask: ContentAsk, owned: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val versions = ask.pick?.versions.orEmpty().joinToString(", ")
    MConfirmDialog(
        title = if (ask.mismatch) "Версия не совпадает" else "Поставить «${ask.title}»?",
        text = if (ask.mismatch) {
            "«${ask.title}» нет под сборку «${ask.targetName}»" + (if (versions.isNotEmpty()) " — есть под $versions" else "") + ". Поставить всё равно?"
        } else {
            "В сборку «${ask.targetName}»"
        },
        confirm = if (ask.paid && !owned && !ask.mismatch) MillidaPaid.buyLabel(ask.pricing) else "Поставить",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        danger = ask.mismatch,
    )
}
