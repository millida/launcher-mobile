package net.millida.launcher.ui.character

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
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
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.CosmeticSections
import net.millida.launcher.core.cosmetics.Outfit
import net.millida.launcher.core.cosmetics.Outfits
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trPlural
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.core.rubies.WardrobeBill
import net.millida.launcher.core.skins.ShowcaseKind
import net.millida.launcher.core.skins.SkinShowcase
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRubyAmount
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSignInGate
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.SkinFigure
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.shop.ItemArt
import net.millida.launcher.ui.shop.RubyShortfallDialog
import net.millida.launcher.ui.shop.rubyWord

private val ART_SIDE = 112.dp

/** How a channel hands out an item that is not sold, as the desktop wardrobe names it. */
private val EARNED_BY get() = mapOf(
    "ACHIEVEMENT" to tr("Достижение"),
    "HOURS" to tr("Часы в игре"),
    "QUEST" to tr("Задание"),
    "SEASON" to tr("Сезон"),
    "PLUS_MONTH" to "PLUS",
    "WELCOME" to tr("Подарок"),
)

private fun lockedNote(item: CosmeticItem): String? = when {
    item.access == "PLUS" -> "PLUS"
    item.onSale -> item.priceRubies?.let { "$it ${rubyWord(it.toLong())}" }
    item.access == "PURCHASE" -> EARNED_BY[item.channel] ?: tr("Не продаётся")
    else -> null
}

/** The shop's art reads the same preview and repaints a colourway by hue, so both screens show one picture. */
internal fun CosmeticItem.art(): RubyItem = RubyItem(
    code = id,
    name = name,
    slot = slot,
    rarity = rarity,
    preview = preview.ifEmpty { null },
    color = tint,
    tintFrom = tintFrom,
)

internal fun LazyGridScope.cosmeticItemsSection(p: CharacterPanel, sectionKey: String) {
    val emotes = sectionKey == CosmeticSections.EMOTES
    val state = p.cosmetics
    val model = p.wardrobe ?: return
    val catalog = state.catalog.items
    val error = state.error
    if (!state.catalogReady) {
        if (error != null) {
            full("cos-error") { MErrorCard(error, onRetry = { p.reloadCosmetics() }) }
        } else {
            skeletons("cos", 4)
        }
        return
    }
    val sections = CosmeticSections.of(catalog.map { it.slot }.toSet())
        .filter { it.key == sectionKey }
    if (sections.isEmpty()) {
        full("cos-empty") { MEmptyState(if (emotes) MIcons.smile else MIcons.shirt, tr("Вещей пока нет")) }
        return
    }
    val section = sections.firstOrNull { it.key == p.itemSection } ?: sections.first()
    val chip = section.chips.firstOrNull { it.key == p.itemChip } ?: section.chips.first()
    if (!p.state.millidaSignedIn) {
        full("cos-sign-in") {
            MSignInGate(onSignIn = { p.signIn() }, title = tr("Войди, чтобы надевать вещи"))
        }
    }
    if (error != null) {
        full("cos-error") { MErrorCard(error, onRetry = { p.reloadCosmetics() }) }
    }
    if (sections.size > 1) {
        full("cos-sections-${section.key}") {
            ChipRow {
                sections.forEach { option ->
                    MChip(option.name, selected = option.key == section.key, onClick = { p.onItemSection(option.key) })
                }
            }
        }
    }
    if (section.chips.size > 1) {
        full("cos-chips-${section.key}") {
            ChipRow {
                section.chips.forEach { option ->
                    MChip(option.name, selected = option.key == chip.key, onClick = { p.onItemChip(option.key) })
                }
            }
        }
    }
    val shelf = catalog.filter { it.slot in chip.slots }
    val wornHere = state.worn.any { it.slot in chip.slots }
    full("cos-title-${chip.key}") {
        ShelfTitle(chip.name) {
            if (wornHere || state.fitting.any { it.slot in chip.slots }) {
                MTextButton(
                    tr("Снять"),
                    onClick = { model.takeOff(chip.slots) },
                    enabled = state.busy.isEmpty(),
                )
            }
            MIconButton(
                MIcons.refresh,
                onClick = { p.reloadCosmetics() },
                label = tr("Обновить"),
                enabled = !state.loading,
            )
        }
    }
    if (shelf.isEmpty()) {
        full("cos-shelf-empty-${chip.key}") { MEmptyState(MIcons.shirt, tr("Здесь пока пусто")) }
        return
    }
    items(shelf, key = { "cos:" + it.id }, contentType = { "tile" }) { item ->
        CosmeticTile(p, item, Modifier.animateItem())
    }
}

/** Cape-slot items of the mod hang next to account capes, as the desktop's cape section shows them. */
internal fun LazyGridScope.cosmeticCapes(p: CharacterPanel) {
    if (p.wardrobe == null) return
    val capes = p.cosmetics.catalog.items.filter { it.slot == "CAPE" }
    if (capes.isEmpty()) return
    full("cos-cape-title") {
        ShelfTitle(tr("Плащи-вещи")) {
            if (p.cosmetics.worn.any { it.slot == "CAPE" }) {
                MTextButton(tr("Снять"), onClick = { p.wardrobe?.takeOff(listOf("CAPE")) }, enabled = p.cosmetics.busy.isEmpty())
            }
        }
    }
    items(capes, key = { "cos-cape:" + it.id }, contentType = { "tile" }) { item ->
        CosmeticTile(p, item, Modifier.animateItem())
    }
}

@Composable
private fun CosmeticTile(p: CharacterPanel, item: CosmeticItem, modifier: Modifier) {
    val state = p.cosmetics
    val locked = state.locked(item)
    ItemTile(
        name = item.name,
        onClick = { p.wardrobe?.tap(item) },
        modifier = modifier,
        rarity = Rarity.of(item.rarity),
        mark = when {
            state.isFitting(item) -> TileMark.Trying
            state.isWorn(item) -> TileMark.Worn
            else -> TileMark.None
        },
        note = if (locked) lockedNote(item) else null,
        locked = locked,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ItemArt(item.art(), side = ART_SIDE)
        }
    }
}

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

internal fun LazyGridScope.showcaseSection(p: CharacterPanel, kind: ShowcaseKind) {
    val state = p.cosmetics
    val cards = state.showcase[kind]
    val failed = state.showcaseFailed[kind]
    when {
        failed != null -> full("showcase-error-${kind.wire}") {
            MErrorCard(failed, onRetry = { p.wardrobe?.loadShowcase(kind, force = true) })
        }
        cards == null -> skeletons("showcase-${kind.wire}", 4)
        cards.isEmpty() -> full("showcase-empty-${kind.wire}") { MEmptyState(MIcons.catalog, tr("Каталог пока пуст")) }
        else -> items(cards, key = { "sc:" + kind.wire + ":" + it.name }, contentType = { "tile" }) { card ->
            CatalogTile(p, SkinShowcase.catalogSkin(card), Modifier.animateItem())
        }
    }
}

private fun things(count: Int): String =
    trPlural(count, tr("{count} вещь", count), tr("{count} вещи", count), tr("{count} вещей", count))

internal fun LazyGridScope.looksSection(p: CharacterPanel) {
    val state = p.cosmetics
    if (p.target != WearTarget.Millida) {
        full("looks-gate") {
            if (p.target == WearTarget.SignedOut) {
                MSignInGate(onSignIn = { p.signIn() }, title = tr("Войди, чтобы сохранять образы"))
            } else {
                MEmptyState(MIcons.looks, tr("Образы собираются на аккаунте Millida"))
            }
        }
        return
    }
    val look = p.currentLook
    if (state.outfits.size < Outfits.LIMIT && look != null) {
        item(key = "look-save", contentType = "tile") {
            ItemTile(
                name = if (state.outfits.isEmpty()) tr("Сохранить образ") else tr("Новый образ"),
                onClick = { p.wardrobe?.saveOutfit(look) },
                modifier = Modifier.animateItem(),
                note = tr("{size} из {LIMIT}", state.outfits.size, Outfits.LIMIT),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MIcon(MIcons.plus, size = 40.dp)
                }
            }
        }
    }
    if (look == null) {
        skeletons("looks", 2)
        return
    }
    items(state.outfits, key = { "look:" + it.id }, contentType = { "tile" }) { outfit ->
        val active = Outfits.same(outfit, look)
        ItemTile(
            name = outfit.name,
            onClick = { if (state.outfitBusy.isEmpty()) p.onWearOutfit(outfit) },
            modifier = Modifier.animateItem(),
            mark = if (active) TileMark.Worn else TileMark.None,
            note = if (state.outfitBusy == outfit.id) null else things(outfit.cosmetics.size + 1 + if (outfit.cape.url.isNotEmpty()) 1 else 0),
            onRemove = { p.onEditOutfit(outfit) },
            toolIcon = MIcons.edit,
            toolLabel = tr("Изменить"),
        ) {
            SkinFigure(url = outfit.skin.url, slim = outfit.skin.slim, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Rename or remove a look; removing asks once more, since a look was put together by hand. */
@Composable
internal fun OutfitEditDialog(outfit: Outfit, onRename: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var name by remember(outfit.id) { mutableStateOf(outfit.name) }
    var removing by remember(outfit.id) { mutableStateOf(false) }
    MDialog(
        title = if (removing) tr("Удалить образ?") else tr("Образ"),
        onDismiss = onDismiss,
        body = {
            if (removing) {
                Text(tr("«{name}» пропадёт с телефона", outfit.name), style = MillidaType.body, color = millida.fgMuted)
            } else {
                MField(
                    value = name,
                    onValueChange = { name = it.take(Outfits.NAME_MAX) },
                    placeholder = tr("Название"),
                    onImeAction = { onRename(name) },
                )
            }
        },
        actions = {
            if (removing) {
                MTextButton(tr("Отмена"), onClick = { removing = false })
                MDangerButton(tr("Удалить"), onClick = onRemove)
            } else {
                MTextButton(tr("Удалить"), onClick = { removing = true })
                MPrimaryButton(tr("Сохранить"), onClick = { onRename(name) }, enabled = name.isNotBlank())
            }
        },
    )
}

/**
 * Locked items on the figure: the bill and the way to pay it. Pieces sold for
 * rubies are bought right here, as on the desktop; when nothing on the figure is
 * sold for rubies (PLUS, earned pieces) the way still leads to the shop.
 */
@Composable
internal fun CosmeticFittingBar(
    fitting: List<CosmeticItem>,
    bill: WardrobeBill,
    signedIn: Boolean,
    buying: Boolean,
    canBuy: Boolean,
    onClear: () -> Unit,
    onBuy: () -> Unit,
    onSignIn: () -> Unit,
    onShop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(
                fill = colors.raised,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(outline = colors.dockOutline),
                corners = PixelCorners.Top,
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text(tr("ПРИМЕРКА"), style = MillidaType.caption, color = colors.info, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fitting.joinToString(", ") { it.name },
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bill.total > 0) MRubyAmount(bill.total)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MSecondaryButton(tr("Снять"), onClick = onClear, modifier = Modifier.weight(1f), enabled = !buying)
            when {
                bill.isEmpty -> MPrimaryButton(tr("В магазин"), onClick = onShop, modifier = Modifier.weight(1f), icon = MIcons.shop)
                !signedIn -> MPrimaryButton(tr("Войти"), onClick = onSignIn, modifier = Modifier.weight(1f), icon = MIcons.login)
                else -> MPrimaryButton(
                    tr("Купить"),
                    onClick = onBuy,
                    modifier = Modifier.weight(1f),
                    enabled = canBuy,
                    icon = MIcons.ruby,
                    loading = buying,
                )
            }
        }
    }
}

/** The wardrobe's checkout: the price is confirmed first; a known shortfall goes the shop's way. */
internal sealed interface TryOnPurchase {
    data class Confirm(val bill: WardrobeBill) : TryOnPurchase
    data class Short(val bill: WardrobeBill, val missing: Long) : TryOnPurchase

    companion object {
        fun of(bill: WardrobeBill, balance: Long?): TryOnPurchase {
            val missing = bill.shortfall(balance)
            return if (missing > 0) Short(bill, missing) else Confirm(bill)
        }
    }
}

@Composable
internal fun TryOnPurchaseDialog(
    prompt: TryOnPurchase,
    onBuy: (WardrobeBill) -> Unit,
    onTopUp: (Int) -> Unit,
    onPacks: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (prompt) {
        is TryOnPurchase.Confirm -> {
            val bill = prompt.bill
            MConfirmDialog(
                title = tr("Купить и надеть?"),
                text = billText(bill),
                confirm = tr("Купить"),
                onConfirm = {
                    onDismiss()
                    onBuy(bill)
                },
                onDismiss = onDismiss,
                danger = false,
            )
        }
        is TryOnPurchase.Short -> RubyShortfallDialog(
            subject = billSubject(prompt.bill),
            missing = prompt.missing,
            onTopUp = onTopUp,
            onPacks = onPacks,
            onDismiss = onDismiss,
        )
    }
}

private fun billText(bill: WardrobeBill): String {
    val subject = billSubject(bill)
    val total = groupDigits(bill.total)
    return trPlural(bill.total, tr("{0} за {1} рубин", subject, total), tr("{0} за {1} рубина", subject, total), tr("{0} за {1} рубинов", subject, total))
}

private fun billSubject(bill: WardrobeBill): String =
    bill.items.singleOrNull()?.let { "«${it.name}»" } ?: things(bill.items.size)
