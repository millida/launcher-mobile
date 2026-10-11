package net.millida.launcher.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.WARDROBE_PURCHASE
import net.millida.launcher.UiState
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.cosmetics.CosmeticSections
import net.millida.launcher.core.cosmetics.Outfit
import net.millida.launcher.core.rubies.WardrobeBill
import net.millida.launcher.core.skins.ShowcaseKind
import net.millida.launcher.core.skins.SkinCatalog
import net.millida.launcher.core.skins.TextureKind
import net.millida.launcher.core.skins.TexturePng
import net.millida.launcher.ui.character.CharacterPanel
import net.millida.launcher.ui.character.CharacterStage
import net.millida.launcher.ui.character.CharacterTab
import net.millida.launcher.ui.character.CosmeticFittingBar
import net.millida.launcher.ui.character.OutfitEditDialog
import net.millida.launcher.ui.character.ConfirmAsk
import net.millida.launcher.ui.character.Fitting
import net.millida.launcher.ui.character.FittingBar
import net.millida.launcher.ui.character.StageCollapse
import net.millida.launcher.ui.character.TryOnPurchase
import net.millida.launcher.ui.character.TryOnPurchaseDialog
import net.millida.launcher.ui.character.WearTarget
import net.millida.launcher.ui.character.WideSectionBar
import net.millida.launcher.ui.character.characterLookOf
import net.millida.launcher.ui.character.wearTargetOf
import net.millida.launcher.ui.character.characterSection
import net.millida.launcher.ui.character.collapsingHeight
import net.millida.launcher.ui.character.icon
import net.millida.launcher.ui.character.lookOf
import net.millida.launcher.ui.character.pickedName
import net.millida.launcher.ui.character.readPicked
import net.millida.launcher.ui.character.rememberStageOutfit
import net.millida.launcher.ui.character.rememberSlimArms
import net.millida.launcher.ui.character.skinFitting
import net.millida.launcher.ui.character.slimArms
import net.millida.launcher.ui.character.stageFile
import net.millida.launcher.ui.character.wearBytes
import java.io.File

private const val MILLIDA_SKINS_URL = "https://millida.net/skins"
private const val SETTLE_MS = 6_000L
private const val STAGE_SHARE = 0.42f
private val STAGE_MIN = 168.dp
private val STAGE_MAX = 380.dp
private const val GRID_COLUMNS = 2
private const val WIDE_STAGE_SHARE = 0.36f

/** The desktop's `.ch-grid-items` cell: 156px, and 140px in windows up to 1100px wide. */
private val WIDE_TILE_MIN = 156.dp
private val WIDE_TILE_MIN_NARROW = 140.dp
private const val WIDE_ROOMY_WIDTH_DP = 1100

@OptIn(ExperimentalAnimationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SkinsScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val feedback = LocalUiFeedback.current
    val colors = millida
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val licence by model.licence.state.collectAsState()
    val mojangCapes by model.mojangCapes.state.collectAsState()
    val wardrobe = model.cosmeticWardrobe
    val cosmetics by wardrobe.state.collectAsState()
    val shop by model.shop.state.collectAsState()
    var purchase by remember { mutableStateOf<TryOnPurchase?>(null) }
    val tryOnBill = remember(cosmetics.fitting, cosmetics.owned) { WardrobeBill.of(cosmetics.fitting, cosmetics.owned) }
    val billed = state.millidaSignedIn && !tryOnBill.isEmpty
    LaunchedEffect(billed) { if (billed) model.shop.refreshBalance() }
    val account = state.account
    val target = wearTargetOf(state)
    val look = characterLookOf(state, licence, target)

    var tab by rememberSaveable { mutableStateOf(CharacterTab.SKIN) }
    var pose by remember { mutableStateOf(SkinPose.Idle) }
    var catalogGroup by rememberSaveable { mutableIntStateOf(0) }
    var fitting by remember { mutableStateOf<Fitting?>(null) }
    var settled by remember { mutableStateOf<Fitting?>(null) }
    var wearing by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<ConfirmAsk?>(null) }
    var seenSignedIn by remember { mutableStateOf(state.millidaSignedIn) }
    var itemSection by rememberSaveable { mutableStateOf("") }
    var itemChip by rememberSaveable { mutableStateOf("") }
    var editingOutfit by remember { mutableStateOf<Outfit?>(null) }
    val gridState = rememberLazyGridState()

    val accountId = account?.id
    LaunchedEffect(accountId, target) {
        fitting = null
        if (target == WearTarget.Licence && accountId != null) model.licence.load(accountId)
    }
    val licenceExpired = state.sessionExpired == AccountKind.MICROSOFT
    LaunchedEffect(licenceExpired) {
        if (!licenceExpired && target == WearTarget.Licence && accountId != null && licence.error != null) {
            model.licence.load(accountId)
        }
    }
    LaunchedEffect(Unit) {
        model.mojangCapes.load()
        if (state.millidaSignedIn && state.gameProfile != null) model.loadSkins()
    }
    LaunchedEffect(state.millidaSignedIn) {
        val signedIn = state.millidaSignedIn
        if (signedIn && !seenSignedIn) model.loadSkins()
        seenSignedIn = signedIn
    }
    LaunchedEffect(look) { settled = null }
    LaunchedEffect(settled) {
        if (settled != null) {
            delay(SETTLE_MS)
            settled = null
        }
    }
    LaunchedEffect(tab) { gridState.scrollToItem(0) }
    val gameUuid = state.gameProfile?.uuid
    LaunchedEffect(accountId, state.millidaSignedIn, gameUuid) {
        wardrobe.load(accountId.orEmpty(), state.millidaSignedIn, gameUuid)
        wardrobe.loadOutfits(accountId.orEmpty())
    }
    val intentReady = cosmetics.wearIntent != null && cosmetics.catalogReady && (cosmetics.wornReady || !cosmetics.signedIn)
    LaunchedEffect(intentReady) {
        if (!intentReady) return@LaunchedEffect
        val first = wardrobe.takeWearIntent().firstOrNull() ?: return@LaunchedEffect
        val section = CosmeticSections.ALL.firstOrNull { section -> section.chips.any { first.slot in it.slots } }
        val chip = section?.chips?.firstOrNull { first.slot in it.slots }
        tab = when {
            first.slot == "CAPE" -> CharacterTab.CAPE
            else -> CharacterTab.entries.firstOrNull { it.section != null && it.section == section?.key } ?: CharacterTab.BODY
        }
        if (section != null && chip != null) {
            itemSection = section.key
            itemChip = chip.key
        }
    }
    LaunchedEffect(catalogGroup) {
        ShowcaseKind.entries.getOrNull(catalogGroup - SkinCatalog.GROUPS.size)?.let { wardrobe.loadShowcase(it) }
    }
    val currentLook = remember(target, state.gameProfile, state.wardrobe, cosmetics.worn, cosmetics.wornReady) {
        lookOf(state, target, cosmetics.worn, cosmetics.wornReady)
    }
    val wearOutfit: (Outfit) -> Unit = { outfit ->
        val now = currentLook
        val cards = state.wardrobe
        wardrobe.wearOutfit(
            outfit,
            wearSkin = {
                if (now != null && now.skin.url == outfit.skin.url && now.skin.slim == outfit.skin.slim) {
                    true
                } else {
                    var done = false
                    val card = cards.firstOrNull {
                        it.id == outfit.skin.wardrobeId && !it.isCape && it.model.equals("slim", ignoreCase = true) == outfit.skin.slim
                    }
                    val job = if (card != null) {
                        model.applyWardrobeItem(card.id) { done = true }
                    } else {
                        model.wearSkinUrl(outfit.skin.url, outfit.name, outfit.skin.slim) { done = true }
                    }
                    job.join()
                    done
                }
            },
            wearCape = {
                if (now != null && now.cape.url == outfit.cape.url) {
                    true
                } else {
                    var done = false
                    val job = when {
                        outfit.cape.url.isEmpty() -> model.takeOffCape { done = true }
                        cards.any { it.id == outfit.cape.wardrobeId && it.isCape } -> model.applyWardrobeItem(outfit.cape.wardrobeId) { done = true }
                        else -> {
                            model.notice("Плаща из образа больше нет в гардеробе — надень плащ заново и пересохрани образ", isError = true)
                            null
                        }
                    }
                    job?.join()
                    done
                }
            },
        )
    }

    val pickSkin = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { readSkin(context, uri, stage = true) }
            val picked = result.skin
            val preview = picked?.preview
            if (picked == null || preview == null) {
                model.notice(result.problem, isError = true)
                return@launch
            }
            val slim = picked.slim ?: look.slim
            fitting = skinFitting(
                target = target,
                key = "pick:" + preview.name,
                title = picked.name,
                url = "file://" + preview.absolutePath,
                slim = slim,
                wear = wearBytes(model, target, picked.bytes, slim, picked.name),
                save = { model.saveTexture(picked.name, picked.bytes, slim) },
            )
        }
    }
    val pickToLibrary = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { readSkin(context, uri, stage = false) }
            val picked = result.skin
            if (picked == null) {
                model.notice(result.problem, isError = true)
                return@launch
            }
            model.saveTexture(picked.name, picked.bytes, picked.slim ?: false) { tab = CharacterTab.SKIN }
        }
    }

    val panel = CharacterPanel(
        state = state,
        model = model,
        licence = licence,
        target = target,
        look = look,
        fittingKey = fitting?.key,
        catalogGroup = catalogGroup,
        feedback = feedback,
        mojangCapes = mojangCapes,
        onFit = { next -> fitting = next },
        onAsk = { next -> ask = next },
        onTab = { next -> tab = next },
        onCatalogGroup = { next -> catalogGroup = next },
        onPickSkin = { pickSkin.launch("image/png") },
        onPickLibrary = { pickToLibrary.launch("image/png") },
        onOrderSkin = { model.openLinkOrNotice(context, MILLIDA_SKINS_URL) },
        cosmetics = cosmetics,
        wardrobe = wardrobe,
        itemSection = itemSection,
        itemChip = itemChip,
        onItemSection = { next ->
            itemSection = next
            itemChip = ""
        },
        onItemChip = { next -> itemChip = next },
        currentLook = currentLook,
        onWearOutfit = wearOutfit,
        onEditOutfit = { outfit -> editingOutfit = outfit },
    )

    val wear: (Fitting) -> Unit = { current ->
        val action = current.wear
        if (action != null && !wearing) {
            wearing = true
            val job = action {
                if (fitting?.key == current.key) {
                    settled = current
                    fitting = null
                }
            }
            job?.invokeOnCompletion { wearing = false }
            if (job == null) wearing = false
        }
    }

    val shown = fitting ?: settled
    val shownSkin = shown?.skinUrl
    val guessedSlim = rememberSlimArms(shownSkin)
    val stageSlim = if (shown != null && shownSkin != null) shown.slim ?: guessedSlim ?: look.slim else look.slim
    val readyRewards = state.rewards.count { it.done && !it.claimed }
    val stageOutfit by rememberStageOutfit(cosmetics.dressed, stageSlim, wardrobe) { lost ->
        model.notice("Не удалось показать на фигуре: " + lost.joinToString(", ") + " — обнови гардероб", isError = true)
    }

    val density = LocalDensity.current
    val stageMax = (LocalConfiguration.current.screenHeightDp.dp * STAGE_SHARE).coerceIn(STAGE_MIN, STAGE_MAX)
    val maxPx = with(density) { stageMax.toPx() }
    val minPx = with(density) { STAGE_MIN.toPx() }
    val stagePx = remember(maxPx) { mutableFloatStateOf(maxPx) }
    val collapse = remember(stagePx, minPx, maxPx) { StageCollapse(stagePx, minPx, maxPx) }
    val barMotion = if (reduced) 0 else MillidaMotion.base
    val imeUp = WindowInsets.isImeVisible
    LaunchedEffect(imeUp) { if (imeUp) stagePx.floatValue = minPx }

    val wide = LocalWideLayout.current
    val shownTabs = remember(cosmetics.catalog.items) {
        val present = CosmeticSections.of(cosmetics.catalog.items.map { it.slot }.toSet()).map { it.key }.toSet()
        CharacterTab.entries.filter { it.section == null || it.section in present }
    }
    val stage: @Composable (Modifier) -> Unit = { placement ->
        CharacterStage(
            nick = look.nick,
            skinUrl = shownSkin ?: look.skinUrl,
            slim = stageSlim,
            capeUrl = shown?.capeUrl ?: look.capeUrl,
            pose = pose,
            trying = fitting != null || cosmetics.fitting.isNotEmpty(),
            onPose = { next -> pose = next },
            outfit = stageOutfit,
            modifier = placement,
        )
    }
    val tabBadge: (CharacterTab) -> Int = { if (it == CharacterTab.REWARDS || it == CharacterTab.CAPE) readyRewards else 0 }
    val wideTile = if (LocalConfiguration.current.screenWidthDp > WIDE_ROOMY_WIDTH_DP) WIDE_TILE_MIN else WIDE_TILE_MIN_NARROW
    val wardrobePane: @Composable ColumnScope.() -> Unit = {
        if (wide) {
            WideSectionBar(
                tabs = shownTabs,
                selected = tab,
                onSelect = { next -> tab = next },
                label = { it.label },
                icon = { it.icon() },
                modifier = Modifier.padding(horizontal = screenPadding),
                badge = tabBadge,
            )
        } else {
            MTabs(
                tabs = shownTabs,
                selected = tab,
                onSelect = { next -> tab = next },
                modifier = Modifier.padding(horizontal = screenPadding),
                label = { it.label },
                icon = { it.icon() },
                badge = tabBadge,
                scrollable = true,
            )
        }
        LazyVerticalGrid(
            columns = if (wide) GridCells.Adaptive(wideTile) else GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = gridState,
            contentPadding = PaddingValues(start = screenPadding, end = screenPadding, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            characterSection(tab, panel)
        }
    }
    val fittingBars: @Composable ColumnScope.() -> Unit = {
        AnimatedContent(
            targetState = fitting,
            transitionSpec = {
                (slideInVertically(tween(barMotion, easing = MillidaMotion.steps(4))) { it } +
                    fadeIn(tween(barMotion, easing = MillidaMotion.steps(4)))) togetherWith
                    (slideOutVertically(tween(barMotion, easing = MillidaMotion.steps(3))) { it } +
                        fadeOut(tween(barMotion, easing = MillidaMotion.steps(3)))) using
                    SizeTransform(clip = false)
            },
            label = "fitting-bar",
            contentKey = { it != null },
        ) { current ->
            if (current != null) {
                FittingBar(
                    fitting = current,
                    wearing = wearing,
                    onCancel = { fitting = null },
                    onWear = { wear(current) },
                    onSignIn = { model.loginMillida() },
                )
            }
        }
        if (fitting == null && cosmetics.fitting.isNotEmpty()) {
            CosmeticFittingBar(
                fitting = cosmetics.fitting,
                bill = tryOnBill,
                signedIn = state.millidaSignedIn,
                buying = shop.isBusy(WARDROBE_PURCHASE),
                canBuy = cosmetics.busy.isEmpty(),
                onClear = wardrobe::clearFitting,
                onBuy = { purchase = TryOnPurchase.of(tryOnBill, shop.balance) },
                onSignIn = model::loginMillida,
                onShop = { model.open(Screen.SHOP) },
            )
        }
    }

    if (wide) {
        Column(
            modifier
                .fillMaxSize()
                .background(colors.bg)
                .imePadding(),
        ) {
            WideTopBar(
                title = null,
                onBack = null,
                actions = {
                    MSecondaryButton("Импорт", onClick = { pickToLibrary.launch("image/png") }, icon = MIcons.download, size = MButtonSize.Small)
                    MPrimaryButton("Загрузить скин", onClick = { pickSkin.launch("image/png") }, icon = MIcons.upload, size = MButtonSize.Small)
                },
            )
            Row(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .weight(WIDE_STAGE_SHARE)
                        .fillMaxHeight()
                        .padding(start = screenPadding, top = 12.dp, bottom = screenPadding),
                ) {
                    stage(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    fittingBars()
                }
                Column(
                    Modifier
                        .weight(1f - WIDE_STAGE_SHARE)
                        .fillMaxHeight()
                        .padding(top = 12.dp),
                    content = wardrobePane,
                )
            }
        }
    } else {
        Column(
            modifier
                .fillMaxSize()
                .background(colors.bg)
                .imePadding()
                .nestedScroll(collapse),
        ) {
            stage(
                Modifier
                    .padding(start = screenPadding, end = screenPadding, top = 8.dp)
                    .collapsingHeight(stagePx, minPx, maxPx),
            )
            Spacer(Modifier.height(10.dp))
            wardrobePane()
            fittingBars()
        }
    }

    purchase?.let { prompt ->
        TryOnPurchaseDialog(
            prompt = prompt,
            onBuy = { bill -> model.buyTryOn(bill.items) },
            onTopUp = model.shop::topUp,
            onPacks = { model.open(Screen.SHOP) },
            onDismiss = { purchase = null },
        )
    }

    editingOutfit?.let { outfit ->
        OutfitEditDialog(
            outfit = outfit,
            onRename = { name ->
                wardrobe.renameOutfit(outfit.id, name)
                editingOutfit = null
            },
            onRemove = {
                wardrobe.removeOutfit(outfit.id)
                editingOutfit = null
            },
            onDismiss = { editingOutfit = null },
        )
    }

    ask?.let { current ->
        MConfirmDialog(
            title = current.title,
            text = current.text,
            confirm = current.action,
            onConfirm = {
                ask = null
                current.run()
            },
            onDismiss = { ask = null },
        )
    }
}

private class PickedSkin(val bytes: ByteArray, val name: String, val slim: Boolean?, val preview: File?)

private class SkinPick(val skin: PickedSkin?, val problem: String = "")

private fun readSkin(context: Context, uri: Uri, stage: Boolean): SkinPick {
    val bytes = readPicked(context, uri) ?: return SkinPick(null, "Файл не прочитался — выбери PNG со скином")
    val problem = try {
        TexturePng.validate(TextureKind.SKIN, bytes)
        null
    } catch (bad: Exception) {
        bad.message?.takeIf { it.isNotBlank() } ?: "Это не скин — нужен PNG 64×64"
    }
    if (problem != null) return SkinPick(null, problem)
    val preview = if (stage) stageFile(context, bytes) else null
    if (stage && preview == null) {
        return SkinPick(null, "Не хватило места для примерки — освободи память телефона")
    }
    return SkinPick(PickedSkin(bytes, pickedName(context, uri, "Скин"), slimArms(bytes), preview))
}
