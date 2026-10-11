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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.catalog.MillidaCard
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.MillidaPaid
import net.millida.launcher.core.catalog.PremiumPlan
import net.millida.launcher.core.catalog.Pricing
import net.millida.launcher.core.catalog.Purchase
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MBusyCard
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionHeader
import net.millida.launcher.ui.MSignInGate
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.humanSize
import net.millida.launcher.ui.millida
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val KEY_MIN = 8

/** The Millida block of the modpack shelf: the site's own packs above Modrinth and CurseForge. */
internal fun LazyListScope.millidaShelf(
    shelf: ShelfState,
    modpack: ModpackJob?,
    onOpen: (MillidaCard) -> Unit,
    onPrimary: (MillidaCard) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onPurchases: () -> Unit,
    onFilters: (MillidaFilters) -> Unit,
    onShowAll: () -> Unit,
) {
    item(key = "millida-head") {
        MSectionHeader(title = "Сборки Millida", action = "Мои покупки", onAction = onPurchases)
    }
    item(key = "millida-filters") {
        MillidaFilterBar(
            filters = shelf.filters,
            facets = shelf.facets,
            total = shelf.total.takeIf { shelf.page > 0 && !shelf.loading },
            onChange = onFilters,
        )
    }
    when {
        shelf.items.isEmpty() && shelf.loading -> items(3, key = { "millida-skeleton-$it" }) { MSkeletonRow() }
        shelf.items.isEmpty() && shelf.error != null -> item(key = "millida-error") {
            MErrorCard(message = shelf.error, onRetry = onRetry)
        }
        shelf.items.isEmpty() -> item(key = "millida-empty") {
            CatalogEmpty(searching = shelf.query.isNotBlank() || shelf.filters.active, onShowAll = onShowAll)
        }
    }
    items(shelf.items, key = { "millida:${it.slug}" }, contentType = { "millida-card" }) { card ->
        MillidaPackCard(
            card = card,
            bought = shelf.open(card),
            locked = shelf.locked(card),
            waiting = card.slug in shelf.waiting,
            job = modpack?.takeIf { it.key == MillidaShelfModel.key(card.slug) },
            busy = modpack != null,
            onOpen = { onOpen(card) },
            onPrimary = { onPrimary(card) },
        )
    }
    when {
        shelf.loadingMore -> item(key = "millida-more-loading") { MSkeletonRow() }
        shelf.more -> item(key = "millida-more") {
            MTextButton(text = "Показать ещё", onClick = onMore, modifier = Modifier.fillMaxWidth(), color = millida.accentText)
        }
    }
    item(key = "foreign-head") { MSectionHeader(title = "Modrinth и CurseForge") }
}

@Composable
private fun MillidaPackCard(
    card: MillidaCard,
    bought: Boolean,
    locked: Boolean,
    waiting: Boolean,
    job: ModpackJob?,
    busy: Boolean,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
) {
    val colors = millida
    MCard(onClick = onOpen, contentPadding = 14.dp) {
        if (!card.cover.isNullOrBlank()) {
            MRemoteImage(
                url = card.cover,
                icon = MIcons.image,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp),
                shape = MillidaShape.control,
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            MRemoteImage(url = card.icon ?: card.cover, icon = MIcons.chest, size = 52.dp)
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
                PriceBadges(card.paid, bought, MillidaPaid.priceTag(card.pricing, card.priceKopecks), card.premium)
                card.versions.firstOrNull()?.let { MBadge(it, colors.fgMuted, colors.surface2) }
            }
            Spacer(Modifier.width(8.dp))
            when {
                job != null -> MButton(text = percentText(job.progress) ?: "Ставим", onClick = {}, size = MButtonSize.Small, loading = true)
                locked -> MPrimaryButton(
                    text = if (waiting) "Ждём оплату" else MillidaPaid.buyLabel(card.pricing),
                    onClick = onPrimary,
                    enabled = !waiting,
                    icon = MIcons.wallet,
                    size = MButtonSize.Small,
                )
                else -> MPrimaryButton(
                    text = "Установить",
                    onClick = onPrimary,
                    enabled = !busy,
                    icon = MIcons.download,
                    size = MButtonSize.Small,
                )
            }
        }
        if (job != null) {
            Spacer(Modifier.height(10.dp))
            MProgressBar(progress = job.progress)
        }
    }
}

@Composable
private fun PriceBadges(paid: Boolean, bought: Boolean, price: String, premium: Boolean) {
    val colors = millida
    if (premium) MBadge("Премиум", colors.warning, colors.warningSoft, icon = MIcons.crown)
    when {
        paid && bought -> MBadge("Куплено", colors.accentText, colors.accentSoft, icon = MIcons.check)
        price.isNotEmpty() -> MBadge(price, colors.info, colors.infoSoft)
    }
}

@Composable
internal fun MillidaDetailSheet(
    detail: MillidaDetail,
    bought: Boolean,
    waiting: Boolean,
    signedIn: Boolean,
    job: ModpackJob?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
    onKey: () -> Unit,
    onRetry: () -> Unit,
    onSite: () -> Unit,
) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss) {
        detail.cover?.takeIf { it.isNotBlank() }?.let { cover ->
            MRemoteImage(
                url = cover,
                icon = MIcons.image,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                shape = MillidaShape.control,
            )
            Spacer(Modifier.height(14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MRemoteImage(url = detail.icon, icon = MIcons.chest, size = 60.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(detail.title, style = MillidaType.dialogTitle, color = colors.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("от ${detail.author}", style = MillidaType.small, color = colors.fgFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PriceBadges(detail.paid, bought, MillidaPaid.priceTag(detail.pricing, detail.priceKopecks), detail.premium)
                    if (detail.game.isNotBlank()) MBadge(detail.game, colors.fgMuted, colors.surface2)
                    if (detail.loader.isNotBlank()) MBadge(loaderName(detail.loader), colors.fgMuted, colors.surface2)
                    if (detail.sizeBytes > 0) MBadge(humanSize(detail.sizeBytes), colors.fgMuted, colors.surface2)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        MillidaDetailAction(detail, bought, waiting, signedIn, job, busy, onPrimary, onKey, onRetry)
        if (detail.summary.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(detail.summary, style = MillidaType.body, color = colors.fgMuted)
        }
        Spacer(Modifier.height(10.dp))
        MTextButton(
            text = "Открыть на сайте",
            onClick = onSite,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = MIcons.externalLink,
        )
    }
}

/**
 * A Millida pack on the catalogue's own item page in a wide window, the
 * frame the Modrinth and CurseForge pages use, as the desktop opens all
 * three with one `ItemPage`: the price and facts in the header, buying or
 * installing on its right, the description below and what the pack runs
 * on with its page on millida.net beside it.
 */
@Composable
internal fun MillidaItemPage(
    detail: MillidaDetail,
    bought: Boolean,
    waiting: Boolean,
    signedIn: Boolean,
    job: ModpackJob?,
    busy: Boolean,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    onKey: () -> Unit,
    onRetry: () -> Unit,
    onSite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val downloads = detail.card?.downloads ?: 0
    ItemPageFrame(
        section = "Сборки Millida",
        cover = detail.cover?.takeIf { it.isNotBlank() },
        iconUrl = detail.icon ?: detail.cover,
        fallbackIcon = MIcons.chest,
        title = detail.title,
        author = detail.author,
        onBack = onBack,
        facts = {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (downloads > 0) DownloadsFact(downloads)
                PriceBadges(detail.paid, bought, MillidaPaid.priceTag(detail.pricing, detail.priceKopecks), detail.premium)
            }
        },
        action = { MillidaDetailAction(detail, bought, waiting, signedIn, job, busy, onPrimary, onKey, onRetry) },
        aside = {
            ItemAsideCard("Работает с") {
                MButtonRow {
                    if (detail.game.isNotBlank()) MBadge(detail.game, colors.fgMuted, colors.surface2)
                    if (detail.loader.isNotBlank()) MBadge(loaderName(detail.loader), colors.fgMuted, colors.surface2)
                    if (detail.sizeBytes > 0) MBadge(humanSize(detail.sizeBytes), colors.fgMuted, colors.surface2)
                }
                Spacer(Modifier.height(10.dp))
                MTextButton(text = "На millida.net", onClick = onSite, icon = MIcons.externalLink)
            }
        },
        modifier = modifier,
    ) {
        MCard {
            when {
                detail.summary.isNotBlank() -> Text(detail.summary, style = MillidaType.body, color = colors.fgMuted)
                detail.loading -> MSkeletonRow()
                else -> Text("Без описания", style = MillidaType.body, color = colors.fgFaint)
            }
        }
    }
}

/**
 * What a Millida pack offers to do next, shared by the sheet and the page:
 * a skeleton while the card loads, the reason it will not run on a phone,
 * the install in progress, or one button that signs in, buys or installs,
 * with the key entry under it for premium packs.
 */
@Composable
private fun MillidaDetailAction(
    detail: MillidaDetail,
    bought: Boolean,
    waiting: Boolean,
    signedIn: Boolean,
    job: ModpackJob?,
    busy: Boolean,
    onPrimary: () -> Unit,
    onKey: () -> Unit,
    onRetry: () -> Unit,
) {
    val refusal = detail.phoneRefusal
    val locked = detail.paid && !bought
    Column(Modifier.fillMaxWidth()) {
        when {
            detail.loading -> {
                MSkeletonRow()
                Spacer(Modifier.height(8.dp))
                MSkeletonRow()
            }
            detail.error != null -> MErrorCard(message = detail.error, onRetry = onRetry)
            refusal != null -> PhoneOnlyNote(refusal)
            job != null -> MBusyCard(label = "Ставим «${job.title}»", progress = job.progress, note = job.stage)
            else -> {
                val needsSignIn = (detail.paid || detail.ownPack) && !signedIn
                MPrimaryButton(
                    text = when {
                        needsSignIn -> "Войти"
                        locked && waiting -> "Ждём оплату"
                        locked -> MillidaPaid.buyLabel(detail.pricing)
                        else -> "Установить"
                    },
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !(locked && waiting) && (locked || needsSignIn || !busy),
                    icon = when {
                        needsSignIn -> MIcons.login
                        locked -> MIcons.wallet
                        else -> MIcons.download
                    },
                    size = MButtonSize.Large,
                )
                if (detail.premium && signedIn && !detail.paid) {
                    Spacer(Modifier.height(6.dp))
                    MTextButton(text = "Ввести ключ", onClick = onKey, modifier = Modifier.fillMaxWidth(), icon = MIcons.key)
                }
            }
        }
    }
}

@Composable
internal fun PhoneOnlyNote(text: String) {
    val colors = millida
    MCard(background = colors.warningSoft, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            MIcon(MIcons.monitor, size = 20.dp, color = colors.warning)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Только на ПК", style = MillidaType.rowTitle, color = colors.fg)
                Spacer(Modifier.height(4.dp))
                Text(text, style = MillidaType.small, color = colors.fgMuted)
            }
        }
    }
}

@Composable
internal fun PhoneRefusalDialog(refusal: PhoneRefusal, onDismiss: () -> Unit) {
    MDialog(
        title = "Только на ПК",
        onDismiss = onDismiss,
        body = { Text(refusal.text, style = MillidaType.body, color = millida.fgMuted) },
        actions = { MPrimaryButton(text = "Понятно", onClick = onDismiss) },
    )
}

@Composable
internal fun BuySheet(ask: BuyAsk, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = millida
    val subscription = ask.pricing == Pricing.SUBSCRIPTION
    val funds = ask.step == BuyStep.FUNDS
    val short = MillidaPaid.shortfallKopecks(ask.priceKopecks, ask.balance)
    val head = when {
        funds -> if (short != null && short > 0) "Не хватает ${MillidaPaid.rubles(short)}" else "Не хватает денег"
        subscription -> "Подписка «${ask.title}»"
        else -> "Купить «${ask.title}»"
    }
    val line = when {
        funds -> "Пополни кошелёк и вернись"
        ask.repriced -> "Цена изменилась"
        subscription -> "Оплата на следующем шаге"
        else -> "Спишем с кошелька Millida"
    }
    MBottomSheet(onDismiss = onDismiss, title = head) {
        Text(MillidaPaid.priceTag(ask.pricing, ask.priceKopecks), style = MillidaType.dialogTitle, color = colors.fg)
        Spacer(Modifier.height(4.dp))
        Text(line, style = MillidaType.small, color = if (ask.repriced && !funds) colors.warning else colors.fgMuted)
        if (!subscription) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Баланс", style = MillidaType.body, color = colors.fgMuted, modifier = Modifier.weight(1f))
                val balance = ask.balance
                if (balance == null) {
                    MSkeleton(height = 16.dp, modifier = Modifier.width(64.dp))
                } else {
                    Text(MillidaPaid.rubles(balance), style = MillidaType.rowTitle, color = colors.fg)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MSecondaryButton(text = "Отмена", onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !ask.busy)
            MPrimaryButton(
                text = when {
                    funds -> "Пополнить"
                    subscription -> "Оформить"
                    else -> "Купить"
                },
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                loading = ask.busy,
                icon = if (funds) MIcons.externalLink else null,
            )
        }
    }
}

@Composable
internal fun KeySheet(
    ask: KeyAsk,
    onRedeem: (String) -> Unit,
    onBuy: () -> Unit,
    onPlan: (PremiumPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    var code by remember(ask.slug) { mutableStateOf("") }
    val offer = ask.offer
    val plans = if (offer != null && offer.planChoice) offer.plans else emptyList()
    val keyEntry = plans.isEmpty() || offer?.acceptsKeys == true
    val price = if (plans.isEmpty()) offer?.priceLabel().orEmpty() else ""
    MBottomSheet(onDismiss = onDismiss, title = ask.title) {
        if (plans.isNotEmpty()) {
            plans.forEach { plan ->
                MPrimaryButton(
                    text = "${plan.title.ifBlank { "Подписка" }} · ${planPrice(plan)}",
                    onClick = { onPlan(plan) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !ask.busy,
                    icon = MIcons.crown,
                )
                Spacer(Modifier.height(8.dp))
            }
        } else if (ask.buyUrl.isNotEmpty() || price.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (price.isNotEmpty()) {
                    Text(price, style = MillidaType.dialogTitle, color = colors.fg, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (ask.buyUrl.isNotEmpty()) {
                    MButton(text = "Купить", onClick = onBuy, variant = MButtonVariant.Primary, trailingIcon = MIcons.externalLink)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (keyEntry) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.lock, size = 16.dp, color = colors.fgSubtle)
                Spacer(Modifier.width(6.dp))
                Text("На этом аккаунте доступа нет", style = MillidaType.small, color = colors.fgSubtle)
            }
            Spacer(Modifier.height(8.dp))
            MField(
                value = code,
                onValueChange = { code = it },
                placeholder = "Ввести ключ",
                leadingIcon = MIcons.key,
                autoCorrectEnabled = false,
                onImeAction = { if (code.trim().length >= KEY_MIN) onRedeem(code) },
            )
            ask.error?.let { error ->
                Spacer(Modifier.height(6.dp))
                Text(error, style = MillidaType.small, color = colors.danger)
            }
            Spacer(Modifier.height(10.dp))
            MSecondaryButton(
                text = "Активировать",
                onClick = { onRedeem(code) },
                modifier = Modifier.fillMaxWidth(),
                enabled = code.trim().length >= KEY_MIN,
                loading = ask.busy,
                icon = MIcons.key,
            )
        }
    }
}

private fun planPrice(plan: PremiumPlan): String = MillidaPaid.rubles(plan.priceKopecks) + when (plan.period) {
    "year" -> "/год"
    "month" -> "/мес"
    else -> ""
}

@Composable
internal fun PurchasesSheet(
    shelf: ShelfState,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Purchase) -> Unit,
    onSite: (Purchase) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = shelf.purchases
    MBottomSheet(onDismiss = onDismiss, title = "Мои покупки") {
        when {
            !signedIn -> MSignInGate(onSignIn = onSignIn, title = "Покупки — в аккаунте Millida")
            shelf.purchasesError != null && rows.isNullOrEmpty() -> MErrorCard(message = shelf.purchasesError, onRetry = onRetry)
            rows == null -> {
                MSkeletonRow()
                Spacer(Modifier.height(8.dp))
                MSkeletonRow()
            }
            rows.isEmpty() -> MEmptyState(icon = MIcons.inbox, title = "Покупок пока нет", primary = false)
            else -> rows.forEachIndexed { index, purchase ->
                if (index > 0) MDivider()
                MListRow(
                    title = purchase.title,
                    subtitle = purchaseLine(purchase),
                    plate = false,
                    leading = { MRemoteImage(url = purchase.iconUrl, icon = MIcons.chest, size = 40.dp) },
                    onClick = { if (purchase.section == MillidaShelfModel.SECTION) onOpen(purchase) else onSite(purchase) },
                    trailing = {
                        MIcon(
                            if (purchase.section == MillidaShelfModel.SECTION) MIcons.chevronRight else MIcons.externalLink,
                            size = 16.dp,
                            color = millida.fgFaint,
                        )
                    },
                )
            }
        }
    }
}

private val UNTIL = DateTimeFormatter.ofPattern("d MMMM", Locale("ru", "RU"))

private fun purchaseLine(purchase: Purchase): String {
    val until = purchase.accessUntil?.let { raw ->
        runCatching { UNTIL.format(Instant.parse(raw).atZone(ZoneId.systemDefault())) }.getOrNull()
    }
    return when {
        until != null -> "Доступ до $until"
        purchase.kind == "SUBSCRIPTION" -> "Подписка"
        else -> "Навсегда"
    }
}

private fun percentText(share: Float?): String? = share?.let { "${(it * 100).toInt()}%" }
