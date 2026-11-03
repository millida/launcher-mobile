package net.millida.launcher.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.cosmetics.Look
import net.millida.launcher.core.cosmetics.Outfit
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.skins.CapeOffer
import net.millida.launcher.core.skins.CatalogSkin
import net.millida.launcher.core.skins.Reward
import net.millida.launcher.core.skins.SkinCatalog
import net.millida.launcher.core.skins.ShowcaseKind
import net.millida.launcher.core.skins.SkinImport
import net.millida.launcher.core.skins.TextureEntry
import net.millida.launcher.core.skins.TextureError
import net.millida.launcher.core.skins.WardrobeItem
import net.millida.launcher.ui.CapeFigure
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSignInGate
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.SkinFigure
import net.millida.launcher.ui.UiFeedback
import net.millida.launcher.ui.millida

private val SIGN_IN_NOTE: String get() = tr("Войди в Millida, чтобы надеть")
private val MOJANG_ONLY_NOTE: String get() = tr("Этот плащ выдаёт Mojang — здесь только примерка")
private const val DESIGN_CAPE_ID = "design:millida"
private const val DESIGN_CAPE_NAME = "Millida"
private const val DESIGN_CAPE_URL = "file:///android_asset/capes/millida.png"
private const val DESIGN_CAPE_KEY = "co:design"

/** The server stores a copy of a catalogue or reward cape when it is put on, under the offer's name. */
private val SERVER_COPY_SOURCES = setOf("catalog", "reward")

private fun wornServerCopy(state: UiState): WardrobeItem? {
    val worn = state.gameProfile?.capeUrl.orEmpty()
    if (worn.isEmpty()) return null
    return state.wardrobe.firstOrNull { it.isCape && it.url == worn && it.source in SERVER_COPY_SOURCES }
}

internal fun skinFitting(
    target: WearTarget,
    key: String,
    title: String,
    url: String,
    slim: Boolean?,
    wear: Wear?,
    save: (() -> Unit)? = null,
): Fitting {
    val locked = wear == null && target == WearTarget.SignedOut
    return Fitting(
        key = key,
        title = title,
        skinUrl = url,
        slim = slim,
        note = if (locked) SIGN_IN_NOTE else null,
        wear = wear,
        save = save,
        signIn = locked,
    )
}

internal fun wearBytes(model: LauncherViewModel, target: WearTarget, png: ByteArray, slim: Boolean, name: String): Wear? {
    if (target == WearTarget.SignedOut) return null
    val licensed = target == WearTarget.Licence
    return { done -> if (licensed) model.licence.wearBytes(png, slim, done) else model.uploadSkin(png, slim, name, done) }
}

internal class CharacterPanel(
    val state: UiState,
    val model: LauncherViewModel,
    val licence: LicenceState,
    val target: WearTarget,
    val look: CharacterLook,
    val fittingKey: String?,
    val skinChip: String,
    val capeChip: String,
    val importOpen: Boolean,
    val feedback: UiFeedback,
    val mojangCapes: List<CapeOffer>,
    val onFit: (Fitting?) -> Unit,
    val onAsk: (ConfirmAsk) -> Unit,
    val onTab: (CharacterTab) -> Unit,
    val onSkinChip: (String) -> Unit,
    val onCapeChip: (String) -> Unit,
    val onImportOpen: (Boolean) -> Unit,
    val onPickSkin: () -> Unit,
    val onPickLibrary: () -> Unit,
    val onOrderSkin: () -> Unit,
    val cosmetics: CosmeticsState = CosmeticsState(),
    val wardrobe: CosmeticsModel? = null,
    val itemSection: String = "",
    val itemChip: String = "",
    val onItemSection: (String) -> Unit = {},
    val onItemChip: (String) -> Unit = {},
    val currentLook: Look? = null,
    val onWearOutfit: (Outfit) -> Unit = {},
    val onEditOutfit: (Outfit) -> Unit = {},
) {
    fun toggle(fitting: Fitting) {
        onFit(if (fittingKey == fitting.key) null else fitting)
    }

    fun pick(worn: Boolean, fitting: () -> Fitting) {
        if (worn) onFit(null) else toggle(fitting())
    }

    fun mark(key: String, worn: Boolean): TileMark = when {
        fittingKey == key -> TileMark.Trying
        worn -> TileMark.Worn
        else -> TileMark.None
    }

    fun capeFitting(key: String, title: String, url: String, wear: Wear?, note: String? = null): Fitting {
        val locked = wear == null && note == null && target == WearTarget.SignedOut
        return Fitting(
            key = key,
            title = title,
            capeUrl = url,
            note = note ?: if (locked) SIGN_IN_NOTE else null,
            wear = wear,
            signIn = locked,
        )
    }

    fun catalogFitting(skin: CatalogSkin, slim: Boolean?): Fitting {
        val url = SkinCatalog.textureUrl(skin)
        return skinFitting(
            target,
            key = "cat:" + skin.key,
            title = skin.label,
            url = url,
            slim = slim,
            wear = wearUrl(url, skin.label),
            save = { model.importSkinByUrl(url, skin.label) },
        )
    }

    fun wearUrl(url: String, name: String): Wear? {
        if (target == WearTarget.SignedOut) return null
        val licensed = target == WearTarget.Licence
        return { done -> if (licensed) model.licence.wearUrl(url, null, done) else model.wearSkinUrl(url, name, null, done) }
    }

    fun wearLibrary(entry: TextureEntry): Wear? {
        if (target == WearTarget.SignedOut) return null
        val licensed = target == WearTarget.Licence
        return { done ->
            if (licensed) {
                model.licence.wearFile(entry.path, entry.slim, done)
            } else {
                val bytes = try {
                    entry.bytes()
                } catch (unreadable: Exception) {
                    null
                }
                if (bytes == null || bytes.isEmpty()) {
                    model.notice(tr("Файл скина не читается — добавь его в библиотеку заново"), isError = true)
                    null
                } else {
                    model.uploadSkin(bytes, entry.slim, entry.name, done)
                }
            }
        }
    }

    fun wearWardrobe(item: WardrobeItem): Wear? {
        if (target == WearTarget.SignedOut) return null
        val licensed = target == WearTarget.Licence
        val slim = item.model.equals("slim", ignoreCase = true)
        return { done -> if (licensed) model.licence.wearUrl(item.url, slim, done) else model.applyWardrobeItem(item.id, done) }
    }

    fun millidaCape(id: String): Wear = { done -> model.applyCape(id, done) }

    fun wardrobeCape(id: String): Wear = { done -> model.applyWardrobeItem(id, done) }

    fun licenceCape(id: String): Wear = { done -> model.licence.setCape(id, done) }

    fun reloadLicence() {
        state.account?.let { model.licence.load(it.id) }
    }

    fun signIn() {
        model.loginMillida()
    }

    fun reloadCosmetics() {
        wardrobe?.load(state.account?.id.orEmpty(), state.millidaSignedIn, state.gameProfile?.uuid)
    }
}

internal fun LazyGridScope.characterSection(tab: CharacterTab, panel: CharacterPanel) {
    when (tab) {
        CharacterTab.SKIN -> skinSection(panel)
        CharacterTab.CAPE -> capeSection(panel)
        CharacterTab.HEAD, CharacterTab.BODY, CharacterTab.BACK, CharacterTab.PETS, CharacterTab.EMOTES ->
            cosmeticItemsSection(panel, tab.section.orEmpty())
        CharacterTab.LOOKS -> looksSection(panel)
        CharacterTab.SHOP -> Unit
    }
}

internal fun LazyGridScope.full(key: String, content: @Composable LazyGridItemScope.() -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "full") { content() }
}

internal fun LazyGridScope.skeletons(prefix: String, count: Int) {
    items(count, key = { "$prefix-skeleton-$it" }, contentType = { "skeleton" }) { SkeletonTile() }
}

/**
 * The desktop's «Скины» section: the «Мои» chip holds the phone library, the
 * account's skins and the upload and order tiles; the other chips are the
 * millida.net showcase with a nick search on top.
 */
private fun LazyGridScope.skinSection(p: CharacterPanel) {
    full("skin-chips") {
        ChipRow {
            MChip(tr("Мои"), selected = p.skinChip == SKIN_CHIP_MINE, onClick = { p.onSkinChip(SKIN_CHIP_MINE) })
            ShowcaseKind.entries.forEach { kind ->
                MChip(kind.title, selected = p.skinChip == kind.wire, onClick = { p.onSkinChip(kind.wire) })
            }
        }
    }
    if (p.importOpen) full("skin-import") { ImportPanel(p) }
    val showcase = ShowcaseKind.entries.firstOrNull { it.wire == p.skinChip }
    if (showcase != null) {
        full("catalog-search") { CatalogSearch(p) }
        showcaseSection(p, showcase)
        return
    }
    if (p.target == WearTarget.SignedOut) {
        full("skin-sign-in") {
            MListRow(
                title = tr("Скины только на этом телефоне"),
                subtitle = tr("Войди в Millida — тогда скин увидят на серверах"),
                icon = MIcons.lock,
                onClick = { p.signIn() },
            )
        }
    }
    if (p.target != WearTarget.SignedOut) full("skin-controls") { SkinControls(p) }
    if (p.target == WearTarget.Millida && p.state.gameProfile?.nameConflict == true) {
        full("skin-conflict") {
            MErrorCard(tr("Игровой ник занят другим аккаунтом — смени его на сайте, иначе скин не подхватится"), onRetry = null)
        }
    }
    if (p.target == WearTarget.Licence) licenceStatus(p)
    full("skin-library-title") {
        ShelfTitle(tr("Мои скины")) {
            if (p.state.millidaSignedIn) RefreshSkins(p)
            MTextButton(tr("Импорт"), onClick = { p.onImportOpen(!p.importOpen) }, icon = MIcons.download)
        }
    }
    libraryTiles(p)
    accountSkinTiles(p)
    item(key = "skin-upload", contentType = "tile") {
        ItemTile(
            name = tr("Загрузить скин"),
            onClick = if (p.target == WearTarget.SignedOut) p.onPickLibrary else p.onPickSkin,
            modifier = Modifier.animateItem(),
            note = "PNG",
        ) { ActionArt(MIcons.upload) }
    }
    item(key = "skin-order", contentType = "tile") {
        ItemTile(
            name = tr("Заказать свой скин"),
            onClick = p.onOrderSkin,
            modifier = Modifier.animateItem(),
            note = "millida.net",
        ) { ActionArt(MIcons.brush) }
    }
}

@Composable
private fun ActionArt(icon: MillidaIcon) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MIcon(icon, size = 40.dp, color = millida.fgSubtle)
    }
}

private fun LazyGridScope.libraryTiles(p: CharacterPanel) {
    val library = p.state.textureLibrary
    items(library, key = { "lib:" + it.file }, contentType = { "tile" }) { entry ->
        val key = "lib:" + entry.file
        val url = "file://" + entry.path.absolutePath
        val title = entry.name.ifEmpty { tr("Скин") }
        ItemTile(
            name = title,
            onClick = { p.toggle(skinFitting(p.target, key, title, url, entry.slim, p.wearLibrary(entry))) },
            modifier = Modifier.animateItem(),
            mark = p.mark(key, worn = false),
            note = if (entry.slim) tr("Тонкие руки") else tr("Классические руки"),
            onRemove = {
                p.onAsk(
                    ConfirmAsk(tr("Удалить скин?"), tr("Он пропадёт из библиотеки на телефоне"), tr("Удалить")) {
                        p.model.deleteTexture(entry.file) { p.feedback.play(UiSoundEvent.Delete) }
                    },
                )
            },
        ) {
            SkinFigure(url = url, slim = entry.slim, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SkinControls(p: CharacterPanel) {
    val colors = millida
    val licensed = p.target == WearTarget.Licence
    MCard {
        Text(tr("МОДЕЛЬ РУК"), style = MillidaType.caption, color = colors.fgSubtle)
        Spacer(Modifier.height(8.dp))
        MSegmented(
            options = listOf(false, true),
            selected = p.look.slim,
            onSelect = { slim -> if (licensed) p.model.licence.setModel(slim) else p.model.setSkinModel(slim) },
            label = { if (it) tr("Тонкие") else tr("Классика") },
        )
        if (licensed && p.licence.profile != null) {
            Spacer(Modifier.height(12.dp))
            MButtonRow {
                MTextButton(
                    tr("Сбросить"),
                    onClick = {
                        p.onAsk(
                            ConfirmAsk(tr("Сбросить скин лицензии?"), tr("Mojang вернёт стандартный скин"), tr("Сбросить")) {
                                p.model.licence.resetSkin { p.onFit(null) }
                            },
                        )
                    },
                    enabled = !p.licence.working,
                )
            }
        }
    }
}

private fun LazyGridScope.licenceStatus(p: CharacterPanel) {
    val licence = p.licence
    val error = licence.error
    if (licence.loading && licence.profile == null) {
        full("licence-loading") { MSkeletonRow() }
    } else if (error != null) {
        val expired = p.state.sessionExpired == AccountKind.MICROSOFT
        full("licence-error") {
            if (expired) {
                MErrorCard(error, onRetry = { p.model.loginMicrosoft() }, retryText = tr("Войти"))
            } else {
                MErrorCard(error, onRetry = { p.reloadLicence() })
            }
        }
    }
}

/** The desktop's «Плащи»: «Все» and, once quests exist, «За задания» with the quest capes. */
private fun LazyGridScope.capeSection(p: CharacterPanel) {
    if (p.state.millidaSignedIn && p.state.rewards.isNotEmpty()) {
        full("cape-chips") {
            ChipRow {
                MChip(tr("Все"), selected = p.capeChip != CAPE_CHIP_QUEST, onClick = { p.onCapeChip(CAPE_CHIP_ALL) })
                MChip(
                    tr("За задания"),
                    selected = p.capeChip == CAPE_CHIP_QUEST,
                    onClick = { p.onCapeChip(CAPE_CHIP_QUEST) },
                    count = p.state.rewards.size,
                )
            }
        }
        if (p.capeChip == CAPE_CHIP_QUEST) {
            rewardsSection(p)
            return
        }
    }
    when (p.target) {
        WearTarget.Licence -> licenceCapes(p)
        WearTarget.Millida -> millidaCapes(p)
        WearTarget.SignedOut -> full("cape-gate") {
            MSignInGate(onSignIn = { p.signIn() }, title = tr("Войди, чтобы носить плащи"))
        }
    }
    officialCapes(p)
    cosmeticCapes(p)
}

private fun LazyGridScope.licenceCapes(p: CharacterPanel) {
    val licence = p.licence
    val profile = licence.profile
    val active = profile?.activeCape
    full("licence-cape-title") {
        ShelfTitle(tr("Плащи лицензии")) {
            if (active != null) {
                MTextButton(
                    tr("Снять"),
                    onClick = { p.model.licence.setCape("") { p.onFit(null) } },
                    enabled = !licence.working,
                )
            }
        }
    }
    licenceStatus(p)
    val capes = profile?.capes.orEmpty()
    if (profile != null && capes.isEmpty()) {
        full("licence-cape-empty") { MEmptyState(MIcons.cape, tr("На лицензии нет плащей")) }
        return
    }
    items(capes, key = { "ms:" + it.id }, contentType = { "tile" }) { cape ->
        val key = "ms:" + cape.id
        val hash = SkinCatalog.textureHash(cape.url)
        val known = p.mojangCapes.firstOrNull { SkinCatalog.textureHash(it.url) == hash }?.name
        val title = cape.alias.ifEmpty { known ?: SkinCatalog.officialCapeOf(cape.url)?.name ?: tr("Плащ") }
        ItemTile(
            name = title,
            onClick = { p.pick(cape.active) { p.capeFitting(key, title, cape.url, p.licenceCape(cape.id)) } },
            modifier = Modifier.animateItem(),
            mark = p.mark(key, worn = cape.active),
        ) {
            CapeFigure(url = cape.url, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun LazyGridScope.millidaCapes(p: CharacterPanel) {
    val state = p.state
    val worn = state.gameProfile?.capeUrl.orEmpty()
    val wornCopy = wornServerCopy(state)
    val offers = state.capes
    val shelfNames = offers.map { it.name }.toSet() + p.mojangCapes.map { it.name } + DESIGN_CAPE_NAME
    val own = state.wardrobe.filter { it.isCape && !(it.source in SERVER_COPY_SOURCES && it.name in shelfNames) }
    val error = state.errorOf(Area.SKINS)
    full("millida-cape-title") {
        ShelfTitle(tr("Плащи Millida")) {
            if (worn.isNotEmpty()) {
                MTextButton(tr("Снять"), onClick = { p.model.takeOffCape { p.onFit(null) } }, enabled = state.busy == null)
            }
            RefreshSkins(p)
        }
    }
    if (error != null) {
        full("millida-cape-error") { MErrorCard(error, onRetry = { p.model.retry(Area.SKINS) }) }
    }
    item(key = DESIGN_CAPE_KEY, contentType = "tile") {
        val isWorn = wornCopy?.name == DESIGN_CAPE_NAME
        ItemTile(
            name = DESIGN_CAPE_NAME,
            onClick = {
                p.pick(isWorn) {
                    p.capeFitting(DESIGN_CAPE_KEY, DESIGN_CAPE_NAME, DESIGN_CAPE_URL, p.millidaCape(DESIGN_CAPE_ID))
                }
            },
            modifier = Modifier.animateItem(),
            mark = p.mark(DESIGN_CAPE_KEY, isWorn),
            note = tr("Плащ лаунчера"),
        ) {
            CapeFigure(url = DESIGN_CAPE_URL, modifier = Modifier.fillMaxSize())
        }
    }
    if (own.isEmpty() && offers.isEmpty()) {
        if (state.isLoading(Area.SKINS)) skeletons("millida-cape", 3)
        return
    }
    items(own, key = { "wc:" + it.id }, contentType = { "tile" }) { item ->
        val key = "wc:" + item.id
        val title = item.name.ifEmpty { tr("Плащ") }
        val isWorn = item.url.isNotEmpty() && item.url == worn
        ItemTile(
            name = title,
            onClick = { p.pick(isWorn) { p.capeFitting(key, title, item.url, p.wardrobeCape(item.id)) } },
            modifier = Modifier.animateItem(),
            mark = p.mark(key, isWorn),
            note = if (item.source.isEmpty() || item.source == "upload") tr("Свой") else null,
            onRemove = {
                p.onAsk(
                    ConfirmAsk(tr("Убрать плащ?"), tr("Он пропадёт из аккаунта Millida"), tr("Убрать")) {
                        p.model.removeWardrobeItem(item.id) { p.feedback.play(UiSoundEvent.Delete) }
                    },
                )
            },
        ) {
            CapeFigure(url = item.url, modifier = Modifier.fillMaxSize())
        }
    }
    items(offers, key = { "co:" + it.id }, contentType = { "tile" }) { cape ->
        val key = "co:" + cape.id
        val title = cape.name.ifEmpty { tr("Плащ") }
        val isWorn = worn.isNotEmpty() && (cape.url == worn || wornCopy?.name == cape.name)
        val progress = if (cape.unlocked) null else capeProgress(cape)
        ItemTile(
            name = title,
            onClick = {
                p.pick(isWorn) {
                    p.capeFitting(key, title, cape.url, if (cape.unlocked) p.millidaCape(cape.id) else null, progress)
                }
            },
            modifier = Modifier.animateItem(),
            rarity = Rarity.of(cape.rarity),
            mark = p.mark(key, isWorn),
            note = progress,
            locked = !cape.unlocked,
        ) {
            CapeFigure(url = cape.url.ifEmpty { null }, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun LazyGridScope.officialCapes(p: CharacterPanel) {
    val owned = p.licence.profile?.capes.orEmpty().map { SkinCatalog.textureHash(it.url) }.toSet()
    val list = p.mojangCapes.filterNot { SkinCatalog.textureHash(it.url) in owned }
    if (list.isEmpty()) return
    val wearable = p.target == WearTarget.Millida
    val licensed = p.target == WearTarget.Licence
    val worn = if (wearable) p.state.gameProfile?.capeUrl.orEmpty() else ""
    val wornCopy = if (wearable) wornServerCopy(p.state) else null
    full("official-cape-title") { ShelfTitle(tr("Плащи Mojang")) }
    items(list, key = { "off:" + it.id }, contentType = { "tile" }) { cape ->
        val key = "off:" + cape.id
        val isWorn = worn.isNotEmpty() && (cape.url == worn || wornCopy?.name == cape.name)
        ItemTile(
            name = cape.name,
            onClick = {
                p.pick(isWorn) {
                    p.capeFitting(
                        key,
                        cape.name,
                        cape.url,
                        wear = if (wearable) p.millidaCape(cape.id) else null,
                        note = if (licensed) MOJANG_ONLY_NOTE else null,
                    )
                }
            },
            modifier = Modifier.animateItem(),
            mark = p.mark(key, isWorn),
            note = if (licensed) tr("Только примерка") else null,
        ) {
            CapeFigure(url = cape.url, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun LazyGridScope.accountSkinTiles(p: CharacterPanel) {
    val state = p.state
    if (!state.millidaSignedIn) return
    val error = state.errorOf(Area.SKINS)
    val skins = state.wardrobe.filterNot { it.isCape }
    if (error != null) {
        full("wardrobe-error") { MErrorCard(error, onRetry = { p.model.retry(Area.SKINS) }) }
    }
    if (skins.isEmpty()) {
        if (state.isLoading(Area.SKINS)) skeletons("wardrobe", 2)
        return
    }
    val worn = state.gameProfile?.skinUrl.orEmpty()
    items(skins, key = { "wr:" + it.id }, contentType = { "tile" }) { item ->
        val key = "wr:" + item.id
        val slim = item.model.equals("slim", ignoreCase = true)
        val title = item.name.ifEmpty { tr("Скин") }
        val isWorn = p.target == WearTarget.Millida && item.url.isNotEmpty() && item.url == worn
        ItemTile(
            name = title,
            onClick = { p.pick(isWorn) { skinFitting(p.target, key, title, item.url, slim, p.wearWardrobe(item)) } },
            modifier = Modifier.animateItem(),
            mark = p.mark(key, isWorn),
            note = if (slim) tr("Тонкие руки") else tr("Классические руки"),
            onRemove = {
                p.onAsk(
                    ConfirmAsk(tr("Убрать из гардероба?"), tr("Скин пропадёт из аккаунта Millida"), tr("Убрать")) {
                        p.model.removeWardrobeItem(item.id) { p.feedback.play(UiSoundEvent.Delete) }
                    },
                )
            },
        ) {
            SkinFigure(url = item.url, slim = slim, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun CatalogTile(p: CharacterPanel, skin: CatalogSkin, modifier: Modifier) {
    val url = SkinCatalog.textureUrl(skin)
    val slim = rememberSlimArms(url)
    ItemTile(
        name = skin.label,
        onClick = { p.toggle(p.catalogFitting(skin, slim)) },
        modifier = modifier,
        mark = p.mark("cat:" + skin.key, worn = false),
        note = if (skin.nick.isEmpty()) "Minecraft" else tr("Игрок"),
    ) {
        SkinFigure(url = url, slim = slim ?: false, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CatalogSearch(p: CharacterPanel) {
    var query by rememberSaveable { mutableStateOf("") }
    val tryNick: () -> Unit = {
        val raw = query.trim()
        if (raw.isNotEmpty()) {
            val nick = try {
                SkinImport.cleanNick(raw)
            } catch (bad: TextureError) {
                null
            }
            if (nick == null) {
                p.model.notice(tr("Ник — от 3 до 16 знаков: латиница, цифры и подчёркивание"), isError = true)
            } else {
                val typed = CatalogSkin(key = "nick:" + nick.lowercase(), label = nick, nick = nick, url = SkinCatalog.skinUrlStrict(nick))
                p.onFit(p.catalogFitting(typed, null))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MField(
            value = query,
            onValueChange = { query = it },
            placeholder = tr("Ник игрока"),
            modifier = Modifier.weight(1f),
            imeAction = ImeAction.Search,
            onImeAction = tryNick,
            leadingIcon = MIcons.search,
            clearable = true,
        )
        Spacer(Modifier.width(8.dp))
        MIconButton(MIcons.eye, onClick = tryNick, label = tr("Примерить"), enabled = query.isNotBlank())
    }
}

@Composable
private fun RefreshSkins(p: CharacterPanel) {
    MIconButton(
        MIcons.refresh,
        onClick = { p.model.loadSkins() },
        label = tr("Обновить"),
        enabled = !p.state.isLoading(Area.SKINS),
    )
}

private fun rewardRank(reward: Reward): Int = when {
    reward.done && !reward.claimed -> 0
    !reward.claimed -> 1
    else -> 2
}

private fun LazyGridScope.rewardsSection(p: CharacterPanel) {
    val state = p.state
    val error = state.errorOf(Area.SKINS)
    full("rewards-title") { ShelfTitle(tr("Плащи за задания")) { RefreshSkins(p) } }
    if (error != null) {
        full("rewards-error") { MErrorCard(error, onRetry = { p.model.retry(Area.SKINS) }) }
    }
    val rewards = state.rewards.sortedBy(::rewardRank)
    if (rewards.isEmpty()) {
        if (state.isLoading(Area.SKINS)) {
            items(3, key = { "rewards-skeleton-$it" }, span = { GridItemSpan(maxLineSpan) }) {
                MSkeleton(height = 150.dp)
            }
        } else if (error == null) {
            full("rewards-empty") { MEmptyState(MIcons.trophy, tr("Наград пока нет")) }
        }
        return
    }
    items(
        rewards,
        key = { "rw:" + it.code },
        span = { GridItemSpan(maxLineSpan) },
        contentType = { "reward" },
    ) { reward ->
        val key = "rw:" + reward.code
        val title = reward.title.ifEmpty { tr("Плащ за задание") }
        RewardCard(
            reward = reward,
            trying = p.fittingKey == key,
            onTry = {
                p.toggle(
                    p.capeFitting(
                        key,
                        title,
                        reward.capeUrl,
                        wear = null,
                        note = if (reward.claimed) tr("Уже твой — надень в «Все»") else tr("Выполни задание и забери плащ"),
                    ),
                )
            },
            onClaim = {
                p.model.claimReward(reward.code) {
                    p.feedback.play(UiSoundEvent.Achievement)
                    p.onCapeChip(CAPE_CHIP_ALL)
                }
            },
            modifier = Modifier.animateItem(),
        )
    }
}

/** The desktop's «Импорт» panel above the skins: a nick or a PNG link, or a file from the phone. */
@Composable
private fun ImportPanel(p: CharacterPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ImportFetch(p)
        MListRow(
            title = tr("Файл с телефона"),
            subtitle = tr("PNG со скином в библиотеку"),
            icon = MIcons.folder,
            onClick = p.onPickLibrary,
        )
    }
}

@Composable
private fun ImportFetch(p: CharacterPanel) {
    val colors = millida
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val fetch: () -> Unit = {
        val text = query.trim()
        if (text.isEmpty()) {
            p.model.notice(tr("Впиши ник игрока или ссылку на PNG"), isError = true)
        } else if (!busy) {
            busy = true
            val done: () -> Unit = {
                query = ""
                p.onImportOpen(false)
                p.onSkinChip(SKIN_CHIP_MINE)
            }
            val job = if (text.startsWith("http", ignoreCase = true)) {
                p.model.importSkinByUrl(text, onDone = done)
            } else {
                p.model.importSkinByNick(text, done)
            }
            job.invokeOnCompletion { busy = false }
        }
    }
    MCard {
        Text(tr("ПО НИКУ ИЛИ ССЫЛКЕ"), style = MillidaType.caption, color = colors.fgSubtle)
        Spacer(Modifier.height(8.dp))
        MField(
            value = query,
            onValueChange = { query = it },
            placeholder = tr("Ник игрока или ссылка на PNG"),
            imeAction = ImeAction.Go,
            onImeAction = fetch,
            leadingIcon = MIcons.link,
            clearable = true,
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(12.dp))
        MPrimaryButton(tr("Забрать"), onClick = fetch, icon = MIcons.download, loading = busy)
    }
}
