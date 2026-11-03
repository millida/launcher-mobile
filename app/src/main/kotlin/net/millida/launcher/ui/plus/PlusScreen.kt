package net.millida.launcher.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage
import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trPlural
import net.millida.launcher.ui.shop.BadgeGlyph
import net.millida.launcher.ui.shop.NickTag
import net.millida.launcher.ui.shop.ShopAssetBox
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.millida.launcher.FriendsTab
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.ShopState
import net.millida.launcher.UiState
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.rubies.MilliLimits
import net.millida.launcher.core.rubies.RubyItem
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MFaintNote
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPlusMark
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MScreen
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.PlusTier
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.openLinkOrNotice
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.shop.ChestArt
import net.millida.launcher.ui.shop.ItemArt
import net.millida.launcher.ui.shop.PlusPass
import net.millida.launcher.ui.shop.RewardScene
import net.millida.launcher.ui.shop.parseInstant
import net.millida.launcher.ui.shop.plusPriceText
import net.millida.launcher.ui.shop.rubles
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

const val PLUS_TITLE = "Millida Plus"

private const val SHARD_LIMIT = 100
private const val SHARD_LIMIT_PLUS = 150
private const val SHOP_ITEMS = 12
private const val SHOP_MIN_PRICE = 350
private const val PROMPT_MS = 2800L
private const val HEADS = "https://api.millida.net/v2/heads/avatar/"
private const val BODIES = "https://api.millida.net/v2/heads/body3d/"
private val LOOT = listOf("FIRE_WINGS_REMASTER", "AXOLOTL", "CHERRY_BLOSSOM_PARTICLES", "NITRO_GEM")
private val SHOP_SLOTS = listOf("WINGS", "PET", "EFFECT", "BACK", "HAT")
private val OTHERS = listOf("Kirill_2011", "dasha_mc", "Nagibator", "xX_Pro_Xx", "SanyaCraft")
private val PROMPTS get() = listOf(tr("Хоррор с зомби на 1.20.1"), tr("Техно и заводы"), tr("Уютная ферма с едой"), tr("Магия и данжи"))
private val DEEP_MODES = setOf("deep", "thorough", "think", "thinking", "enhanced", "pro")
private val NICK = Regex("^[A-Za-z0-9_]{1,16}$")
private val UNTIL: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMMM", I18n.javaLocale).withZone(ZoneId.of("Europe/Moscow"))
private val REFERRAL = listOf(1 to 3, 5 to 14, 10 to 30)
private val EXTRA_LABEL get() = mapOf(
    "shaders" to tr("Шейдеры"),
    "resourcepacks" to tr("Ресурспаки"),
    "maps" to tr("Карты"),
    "seeds" to tr("Сиды"),
    "skins" to tr("Скины"),
    "server" to tr("На сервер"),
)

private fun chestWord(n: Int): String = trPlural(n, tr("сундук"), tr("сундука"), tr("сундуков"))

/** The whole-item chance from a chest for a payer, by the live rules: «6–12 %», "" when the service does not say. */
private fun wholeChance(shop: ShopState): String {
    val chances = shop.rules?.chests?.items.orEmpty().map { it.wholePayerPct }.filter { it > 0 }
    if (chances.isEmpty()) return ""
    val low = chances.min()
    val high = chances.max()
    fun pct(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString().replace('.', ',')
    return if (low == high) "${pct(low)} %" else "${pct(low)}–${pct(high)} %"
}

/** Shop items the month's rubies pay for, a mix across the visible slots: the desktop's ruby gallery. */
private fun shopGallery(items: List<CosmeticItem>): List<CosmeticItem> {
    val bySlot = LinkedHashMap<String, MutableList<CosmeticItem>>()
    val seen = HashSet<String>()
    for (item in items) {
        val base = item.baseId.ifEmpty { item.id }
        val price = item.priceRubies ?: continue
        if (item.channel != "SHOP" || item.staffOnly || item.preview.isEmpty() || item.name.startsWith("(")) continue
        if (price <= SHOP_MIN_PRICE || price > PlusPass.RUBIES || item.slot !in SHOP_SLOTS || !seen.add(base)) continue
        bySlot.getOrPut(item.slot) { ArrayList() }.add(item)
    }
    val mixed = ArrayList<CosmeticItem>()
    var round = 0
    while (mixed.size < SHOP_ITEMS) {
        val next = SHOP_SLOTS.mapNotNull { bySlot[it]?.getOrNull(round) }
        if (next.isEmpty()) break
        mixed += next
        round += 1
    }
    return mixed.take(SHOP_ITEMS)
}

/**
 * Millida PLUS, the desktop's page chapter by chapter: the hero with its chips,
 * the month's rubies, Milli, chests and whole items, the PLUS badge in the
 * game, how it works, without and with PLUS, buy or get it for friends, and
 * the questions. The payment is the shop's own path: the gateway in a browser.
 */
@Composable
fun PlusScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val shop by model.shop.state.collectAsStateWithLifecycle()
    val cosmetics by model.cosmeticWardrobe.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val signedIn = state.millidaSignedIn

    LaunchedEffect(signedIn) { model.shop.openPlus(signedIn) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.shop.resume() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val link = shop.link
    LaunchedEffect(link?.serial) {
        if (link != null) {
            model.openLinkOrNotice(context, link.url)
            model.shop.consumeLink(link.serial)
        }
    }

    val plus = shop.plus.data
    val active = plus?.active == true
    val until = parseInstant(plus?.paidUntil)?.let { UNTIL.format(it) }.orEmpty()
    val price = plusPriceText(plus, PlusTier.PLUS).ifEmpty {
        shop.rules?.packs?.plus?.priceKopecks?.takeIf { it > 0 }?.let(::rubles).orEmpty()
    }
    val whole = wholeChance(shop)
    val milli = shop.milli
    val items = cosmetics.catalog.items
    val gallery = remember(items) { shopGallery(items) }
    val loot = remember(items) { LOOT.mapNotNull { id -> items.firstOrNull { it.id == id && it.preview.isNotEmpty() } } }
    val nick = state.account?.nick?.takeIf { NICK.matches(it) }
    val buy: (PlusTier) -> Unit = { tier -> if (signedIn) model.shop.subscribePlus(tier) else model.loginMillida() }
    val cta: @Composable () -> Unit = {
        PlusCta(
            active = active,
            canceled = plus?.canceled == true,
            until = until,
            waiting = model.shop.paymentWaiting(PlusTier.PLUS),
            busy = shop.isBusy("plus"),
            onBuy = { buy(PlusTier.PLUS) },
        )
    }

    MScreen(modifier = modifier, title = PLUS_TITLE, onBack = { model.back() }, spacing = 14.dp) {
        item(key = "hero") { Hero(price, cta, waiting = model.shop.paymentWaiting(PlusTier.PLUS) && !active, chips = heroChips(milli, whole, loot.firstOrNull())) }
        item(key = "rubies") { RubiesBlock(shop, gallery) }
        if (milli != null) item(key = "milli") { MilliBlock(milli) }
        item(key = "chests") { ChestsBlock(whole, loot, cta) }
        item(key = "badge") { BadgeBlock(nick, shop.stats?.downloads) }
        item(key = "how") { HowBlock(price) }
        item(key = "compare") { CompareBlock(milli, whole) }
        item(key = "buy") {
            BuyBlock(
                shop = shop,
                active = active,
                cta = cta,
                waiting = { tier -> model.shop.paymentWaiting(tier) },
                onBuy = buy,
                onFriends = {
                    model.social.setTab(FriendsTab.INVITE)
                    model.open(Screen.FRIENDS)
                },
            )
        }
        item(key = "faq") { FaqBlock(price, milli) }
    }

    val reveal = shop.reveal
    if (reveal != null) {
        RewardScene(
            reveal = reveal,
            onDone = { model.shop.dismissReveal(reveal.serial) },
            onOpenChests = { model.shop.dismissReveal(reveal.serial) },
        )
    }
}

private class HeroChip(val art: @Composable () -> Unit, val title: String, val sub: String)

private fun heroChips(milli: MilliLimits?, whole: String, lootItem: CosmeticItem?): List<HeroChip> = buildList {
    add(HeroChip({ MIcon(MIcons.ruby, size = 30.dp) }, tr("{0} рубинов", groupDigits(PlusPass.RUBIES)), tr("За 28 дней входа")))
    add(HeroChip({ ChestArt("LEGEND", side = 32.dp) }, "${PlusPass.CHESTS} ${chestWord(PlusPass.CHESTS)}", tr("Легендарный — в конце")))
    if (milli != null) add(HeroChip({ MIcon(MIcons.sparkle, size = 28.dp, color = Rarity.EPIC.color) }, tr("Милли: {day} в день", milli.plus.day), tr("Вместо {day}", milli.free.day)))
    add(HeroChip({ MIcon(MIcons.shard, size = 28.dp) }, tr("Осколки ×{SHARD_BOOST}", PlusPass.SHARD_BOOST), tr("{SHARD_LIMIT_PLUS} в день вместо {SHARD_LIMIT}", SHARD_LIMIT_PLUS, SHARD_LIMIT)))
    if (whole.isNotEmpty() && lootItem != null) {
        add(HeroChip({ CatalogArt(lootItem, 32) }, tr("Целая вещь"), tr("{whole} из сундука", whole)))
    }
    add(HeroChip({ MPlusMark(height = 14.dp) }, tr("Значок PLUS"), tr("В табе и над головой")))
}.take(6)

@Composable
private fun CatalogArt(item: CosmeticItem, side: Int) {
    ItemArt(RubyItem(code = item.id, name = item.name, slot = item.slot, rarity = item.rarity, preview = item.preview), side = side.dp)
}

@Composable
private fun PlusCta(active: Boolean, canceled: Boolean, until: String, waiting: Boolean, busy: Boolean, onBuy: () -> Unit) {
    val colors = millida
    if (active) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .pixelSurface(colors.accentSoft, MillidaCut.control)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MIcon(MIcons.check, size = 16.dp, color = colors.accentText)
            Spacer(Modifier.width(8.dp))
            Text(
                if (canceled) tr("PLUS до {until} · без продления", until) else tr("PLUS активен до {until}", until),
                style = MillidaType.body.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
            )
        }
    } else {
        MPrimaryButton(if (waiting) tr("Открыть оплату") else tr("Оформить PLUS"), onClick = onBuy, icon = MIcons.crown, size = MButtonSize.Large, loading = busy)
    }
}

/**
 * The first screen, the desktop's .lp-hero: the dark gold scene, the title and
 * price, the call to subscribe, the player in gold armour in its light, and
 * the chips — around the figure when the screen is wide, under it otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(price: String, cta: @Composable () -> Unit, waiting: Boolean, chips: List<HeroChip>) {
    val wide = LocalWideLayout.current
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(MillidaPixel.heroBottom, MillidaCut.plate)
            .drawBehind {
                drawRect(Brush.linearGradient(0f to MillidaPixel.heroTop, 0.58f to MillidaPixel.heroMiddle, 1f to MillidaPixel.heroBottom, start = Offset(size.width, 0f), end = Offset(0f, size.height)))
                drawRect(
                    Brush.radialGradient(
                        0f to MillidaPixel.heroGlow,
                        0.7f to MillidaPixel.heroGlow.copy(alpha = 0f),
                        center = Offset(size.width / 2f, size.height * 0.7f),
                        radius = size.width * 0.5f,
                    ),
                )
                val step = 32.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawRect(MillidaPixel.heroGrid, Offset(x, 0f), Size(1.dp.toPx(), size.height))
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawRect(MillidaPixel.heroGrid, Offset(0f, y), Size(size.width, 1.dp.toPx()))
                    y += step
                }
            }
            .padding(start = 18.dp, end = 18.dp, top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("ПОДПИСКА MILLIDA"), style = MillidaType.caption.copy(letterSpacing = 0.12.em), color = millida.accent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Millida", style = MillidaType.sectionTitle.copy(fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold), color = MillidaPixel.white)
            Spacer(Modifier.width(10.dp))
            MPlusMark(height = 40.dp, shine = true)
        }
        if (price.isNotEmpty()) {
            Text(tr("{price} / 28 дней · отмена в любой день", price), style = MillidaType.body.copy(fontWeight = FontWeight.SemiBold), color = MillidaPixel.whiteSoft, textAlign = TextAlign.Center)
        }
        cta()
        if (waiting) Text(tr("Ждём оплату в браузере"), style = MillidaType.small, color = MillidaPixel.whiteSoft)
        val rig: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .size(300.dp)
                        .drawBehind {
                            drawCircle(
                                Brush.radialGradient(
                                    0f to MillidaPixel.heroLight,
                                    0.55f to MillidaPixel.heroLight.copy(alpha = 0.1f),
                                    1f to MillidaPixel.heroLight.copy(alpha = 0f),
                                    center = center,
                                    radius = size.minDimension / 2f,
                                ),
                            )
                        },
                )
                ShopAssetBox("plus/hero-gold.webp", Modifier.height(if (wide) 300.dp else 260.dp).aspectRatio(460f / 701f))
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).padding(bottom = 36.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    chips.take(3).forEachIndexed { i, chip -> HeroChipView(chip, Modifier.padding(end = if (i == 1) 24.dp else 0.dp)) }
                }
                rig()
                Column(Modifier.weight(1f).padding(bottom = 36.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    chips.drop(3).forEachIndexed { i, chip -> HeroChipView(chip, Modifier.padding(start = if (i == 1) 24.dp else 0.dp)) }
                }
            }
        } else {
            rig()
        }
    }
}

@Composable
private fun HeroChipView(chip: HeroChip, modifier: Modifier = Modifier) {
    Row(
        modifier
            .pixelSurface(MillidaPixel.white, MillidaCut.control)
            .padding(start = 10.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { chip.art() }
        Column {
            Text(chip.title, style = MillidaType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold), color = MillidaPixel.heroInk, maxLines = 1)
            Text(chip.sub, style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = MillidaPixel.heroInk.copy(alpha = 0.62f), maxLines = 1)
        }
    }
}

/** A chapter of the page: a big title with its accented part highlighted where it stands, an optional line, the body and a foot. */
@Composable
private fun Block(
    title: String,
    accent: String,
    sub: String? = null,
    foot: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = millida
    MCard(contentPadding = 16.dp) {
        val heading = MillidaType.sectionTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Black)
        val at = if (accent.isEmpty()) -1 else title.lastIndexOf(accent)
        val before = (if (at < 0) title else title.substring(0, at)).trim()
        val after = if (at < 0) "" else title.substring(at + accent.length).trim()
        Row(verticalAlignment = Alignment.Bottom) {
            if (before.isNotEmpty()) Text(before, style = heading, color = colors.fg)
            if (at >= 0) {
                if (before.isNotEmpty()) Spacer(Modifier.width(6.dp))
                Text(accent, style = heading, color = Rarity.LEGENDARY.color)
            }
            if (after.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(after, style = heading, color = colors.fg)
            }
        }
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(sub, style = MillidaType.small, color = colors.fgSubtle)
        }
        Spacer(Modifier.height(12.dp))
        content()
        if (foot != null) {
            Spacer(Modifier.height(14.dp))
            foot()
        }
    }
}

@Composable
private fun Stat(value: String, caption: String, gold: Boolean = false, icon: MillidaIcon? = null, modifier: Modifier = Modifier) {
    val colors = millida
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                MIcon(icon, size = 24.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(value, style = MillidaType.pixelLarge, color = if (gold) Rarity.LEGENDARY.color else colors.fg, maxLines = 1)
        }
        Text(caption, style = MillidaType.small, color = colors.fgSubtle)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RubiesBlock(shop: ShopState, gallery: List<CosmeticItem>) {
    val colors = millida
    val small = shop.rules?.packs?.items.orEmpty().minByOrNull { it.kopecks }
    val rate = shop.rules?.packs?.rubiesPerRuble ?: 0
    Block(
        title = tr("{0} рубинов в месяц", groupDigits(PlusPass.RUBIES)),
        accent = groupDigits(PlusPass.RUBIES),
        sub = small?.let { tr("«{title}» за {0} — {1}", it.title, rubles(it.kopecks), groupDigits(it.rubies)) },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(groupDigits(PlusPass.RUBIES), tr("за 28 дней входа"), gold = true, icon = MIcons.ruby)
            if (rate > 0) Stat(rubles((PlusPass.RUBIES / rate.toDouble()).roundToLong() * 100), tr("по цене магазина"))
            Stat("+${groupDigits(PlusPass.FREE_RUBIES)}", tr("бесплатная строка остаётся"))
        }
        if (gallery.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gallery.forEach { item ->
                    Column(
                        Modifier
                            .width(92.dp)
                            .pixelSurface(colors.surface2, MillidaCut.control)
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        CatalogArt(item, 64)
                        Text(item.name, style = MillidaType.small.copy(fontSize = 11.5.sp), color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MIcon(MIcons.ruby, size = 12.dp)
                            Spacer(Modifier.width(3.dp))
                            Text(groupDigits((item.priceRubies ?: 0).toLong()), style = MillidaType.pixelSmall, color = colors.fgMuted)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MilliBlock(milli: MilliLimits) {
    val colors = millida
    val reduced = LocalReducedMotion.current
    var prompt by rememberSaveable { mutableIntStateOf(0) }
    var hold by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hold, reduced) {
        if (hold || reduced) return@LaunchedEffect
        while (true) {
            delay(PROMPT_MS)
            prompt = (prompt + 1) % PROMPTS.size
        }
    }
    val extras = milli.plusExtras.filter { it in EXTRA_LABEL }
    val packExtras = extras.filter { it != "server" }
    val deep = milli.plusModes.any { it.lowercase() in DEEP_MODES }
    Block(title = tr("Милли собирает больше"), accent = tr("больше"), sub = tr("ИИ-сборщик в каталоге и на сайте")) {
        Column(
            Modifier
                .fillMaxWidth()
                .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.sparkle, size = 30.dp, color = Rarity.EPIC.color)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(tr("Милли с PLUS"), style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg)
                    Text(tr("Опиши, во что хочешь играть"), style = MillidaType.small, color = colors.fgSubtle)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .pixelSurface(colors.inset, MillidaCut.control)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(PROMPTS[prompt], modifier = Modifier.weight(1f).animateContentSize(), style = MillidaType.body, color = colors.fg, maxLines = 1)
                Text(tr("Собрать"), style = MillidaType.small.copy(fontWeight = FontWeight.Black), color = colors.accentText)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PROMPTS.forEachIndexed { index, text ->
                    val on = index == prompt
                    Box(
                        Modifier
                            .pixelSurface(if (on) colors.accentSoft else colors.surface, MillidaCut.badge)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                hold = true
                                prompt = index
                            }
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    ) { Text(text, style = MillidaType.small, color = if (on) colors.accentText else colors.fgMuted, maxLines = 1) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(trPlural(milli.plus.day, tr("{day} запрос в день", milli.plus.day), tr("{day} запроса в день", milli.plus.day), tr("{day} запросов в день", milli.plus.day)), style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg)
        Spacer(Modifier.height(6.dp))
        LimitBar(tr("Без PLUS"), milli.free.day, (milli.free.day / milli.plus.day.toFloat()).coerceIn(0.04f, 1f), colors.fgSubtle)
        Spacer(Modifier.height(4.dp))
        LimitBar("PLUS", milli.plus.day, 1f, Rarity.LEGENDARY.color)
        Spacer(Modifier.height(6.dp))
        Text(tr("{0} в месяц · без PLUS — {month}", groupDigits(milli.plus.month.toLong()), milli.free.month), style = MillidaType.small, color = colors.fgSubtle)
        if (packExtras.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(tr("В сборке не только моды"), style = MillidaType.small.copy(fontWeight = FontWeight.Black), color = colors.fg)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                packExtras.forEach { kind ->
                    Box(Modifier.pixelSurface(colors.surface2, MillidaCut.badge).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(EXTRA_LABEL.getValue(kind), style = MillidaType.small, color = colors.fgMuted)
                    }
                }
            }
        }
        if ("server" in extras) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Сборка на сервер · одной кнопкой на Millida Hosting"), style = MillidaType.small, color = colors.fgMuted)
        }
        if (deep) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Думает дольше · проверяет сборку перед тем как отдать"), style = MillidaType.small, color = colors.fgMuted)
        }
    }
}

@Composable
private fun LimitBar(label: String, value: Int, share: Float, tone: Color) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(72.dp), style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
        Box(Modifier.weight(1f).height(18.dp).pixelSurface(colors.inset, 2.dp)) {
            Box(Modifier.fillMaxWidth(share).height(18.dp).pixelSurface(tone, 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(value.toString(), style = MillidaType.pixelSmall, color = colors.fg, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChestsBlock(whole: String, loot: List<CosmeticItem>, cta: @Composable () -> Unit) {
    Block(title = tr("Сундуки и целые вещи"), accent = tr("целые вещи"), foot = cta) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(
                art = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        ChestArt("RARE", side = 44.dp)
                        ChestArt("LEGEND", side = 64.dp)
                        ChestArt("EPIC", side = 44.dp)
                    }
                },
                title = "${PlusPass.CHESTS} ${chestWord(PlusPass.CHESTS)}",
                sub = tr("{RARE} редких · {EPIC} эпических · {LEGEND} легендарный", PlusPass.RARE, PlusPass.EPIC, PlusPass.LEGEND),
            )
            if (whole.isNotEmpty()) {
                InfoCard(
                    art = { Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { loot.forEach { CatalogArt(it, 40) } } },
                    title = tr("Целая вещь {whole}", whole),
                    sub = tr("Без PLUS — только фрагменты"),
                )
            }
            InfoCard(
                art = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MIcon(MIcons.shard, size = 52.dp)
                        Text("×${PlusPass.SHARD_BOOST}", style = MillidaType.pixelLarge, color = millida.fg)
                    }
                },
                title = tr("Осколки ×{SHARD_BOOST}", PlusPass.SHARD_BOOST),
                sub = tr("{SHARD_LIMIT_PLUS} в день вместо {SHARD_LIMIT}", SHARD_LIMIT_PLUS, SHARD_LIMIT),
            )
        }
    }
}

@Composable
private fun InfoCard(art: @Composable () -> Unit, title: String, sub: String) {
    val colors = millida
    Column(
        Modifier
            .width(200.dp)
            .heightIn(min = 150.dp)
            .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.height(70.dp), contentAlignment = Alignment.Center) { art() }
        Text(title, style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg, textAlign = TextAlign.Center)
        Text(sub, style = MillidaType.small, color = colors.fgSubtle, textAlign = TextAlign.Center)
    }
}

/**
 * The badge as the server shows it (the desktop's .lp-mc): a game scene, the
 * TAB list with the player's row in gold, two other players and the player
 * in the middle under a gold name tag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeBlock(nick: String?, downloads: Long?) {
    val colors = millida
    val wide = LocalWideLayout.current
    val shown = nick ?: "PixelKate"
    val rows = listOf(OTHERS[0], shown) + OTHERS.drop(1)
    Block(title = tr("Значок PLUS в игре"), accent = tr("в игре"), sub = tr("так тебя видят на сервере")) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .heightIn(min = 300.dp)
                .pixelSurface(MillidaPixel.sceneBack, MillidaCut.plate),
        ) {
            ShopAssetBox("plus/scene.webp", Modifier.matchParentSize(), scale = ContentScale.Crop)
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(Brush.verticalGradient(0f to MillidaPixel.sceneShade, 0.45f to MillidaPixel.sceneShade.copy(alpha = 0.2f), 1f to MillidaPixel.sceneShade))
                    },
            )
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.8f)
                    .background(MillidaPixel.tabBack)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { player ->
                    val you = player == shown
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(21.dp)
                            .background(if (you) MillidaPixel.tabYou else MillidaPixel.tabRow)
                            .padding(start = 2.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MAvatar(url = HEADS + java.net.URLEncoder.encode(player, "UTF-8") + "?size=32", size = 16.dp, ring = Color.Transparent)
                        if (you) BadgeGlyph(plus = true, size = 12.dp)
                        Text(player, modifier = Modifier.weight(1f), style = MillidaType.pixelSmall.copy(fontSize = 12.sp), color = MillidaPixel.white, maxLines = 1)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            for (bar in 1..5) Box(Modifier.size(width = 2.dp, height = (1 + bar * 2).dp).background(MillidaPixel.badgeGreen))
                        }
                    }
                }
            }
            if (wide) {
                listOf("dasha_mc" to 0.26f, "Nagibator" to 0.74f).forEachIndexed { i, (player, at) ->
                    Column(
                        Modifier
                            .align(BiasAlignment(at * 2f - 1f, 1f))
                            .graphicsLayer { alpha = 0.86f },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (i == 0) SceneNote(tr("Все остальные"), gold = false)
                        Text(player, modifier = Modifier.background(MillidaPixel.nameTagBack).padding(horizontal = 8.dp, vertical = 3.dp), style = MillidaType.pixel.copy(fontSize = 14.sp), color = MillidaPixel.white, maxLines = 1)
                        AsyncImage(model = BODIES + java.net.URLEncoder.encode(player, "UTF-8") + ".webp?size=256", contentDescription = null, modifier = Modifier.height(140.dp).aspectRatio(0.6f), filterQuality = FilterQuality.None)
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SceneNote(if (nick != null) tr("Это ты") else tr("Так выглядит твой ник"), gold = true)
                NickTag(shown, plus = true, fontSize = 18f)
                AsyncImage(
                    model = BODIES + java.net.URLEncoder.encode(shown, "UTF-8") + ".webp?size=384",
                    contentDescription = tr("Скин игрока {shown}", shown),
                    modifier = Modifier.height(if (wide) 200.dp else 150.dp).aspectRatio(0.6f),
                    filterQuality = FilterQuality.None,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val where = listOfNotNull(tr("В табе"), tr("Над головой"), tr("В профиле на millida.net"), downloads?.let { tr("Видят {0} скачавших лаунчер", groupDigits(it)) })
            where.forEach { text ->
                Box(Modifier.pixelSurface(colors.surface2, MillidaCut.badge).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(text, style = MillidaType.small, color = colors.fgMuted)
                }
            }
        }
    }
}

@Composable
private fun SceneNote(text: String, gold: Boolean) {
    Text(
        text,
        modifier = Modifier
            .pixelSurface(if (gold) Rarity.LEGENDARY.color else MillidaPixel.sceneNote, MillidaCut.badge)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        style = MillidaType.small.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold),
        color = if (gold) Rarity.ink else MillidaPixel.white,
        maxLines = 1,
    )
}

@Composable
private fun HowBlock(price: String) {
    val colors = millida
    val wide = LocalWideLayout.current
    val steps = listOf(
        tr("Оформи PLUS") to if (price.isNotEmpty()) tr("{price} / 28 дней", price) else tr("Раз в 28 дней"),
        tr("Заходи в лаунчер") to tr("Клетка наград за день"),
        tr("Забирай") to tr("Рубины, сундуки, осколки"),
    )
    Block(title = tr("Как это работает"), accent = tr("работает")) {
        val card: @Composable (Int, Pair<String, String>, Modifier) -> Unit = { index, step, cardModifier ->
            Column(
                cardModifier
                    .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("${index + 1}", style = MillidaType.pixelLarge, color = Rarity.LEGENDARY.color)
                Text(step.first, style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg)
                Text(step.second, style = MillidaType.small, color = colors.fgSubtle)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { index, step ->
                    card(index, step, Modifier.weight(1f))
                    if (index < steps.lastIndex) MIcon(MIcons.chevronRight, size = 16.dp, color = colors.fgSubtle)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { index, step -> card(index, step, Modifier.fillMaxWidth()) }
            }
        }
        MFaintNote(tr("Отмена в профиле — оплаченные дни остаются"))
    }
}

@Composable
private fun CompareBlock(milli: MilliLimits?, whole: String) {
    val colors = millida
    val rows = buildList {
        add(Triple(tr("Рубины за 28 дней"), groupDigits(PlusPass.FREE_RUBIES), groupDigits(PlusPass.FREE_RUBIES + PlusPass.RUBIES)))
        add(Triple(tr("Сундуки за 28 дней"), PlusPass.FREE_CHESTS.toString(), (PlusPass.FREE_CHESTS + PlusPass.CHESTS).toString()))
        if (whole.isNotEmpty()) add(Triple(tr("Целая вещь из сундука"), tr("Нет"), whole))
        add(Triple(tr("Осколки в день"), SHARD_LIMIT.toString(), "$SHARD_LIMIT_PLUS, ×${PlusPass.SHARD_BOOST}"))
        if (milli != null) add(Triple(tr("Милли в день"), milli.free.day.toString(), milli.plus.day.toString()))
        val extras = milli?.plusExtras.orEmpty()
        if (extras.any { it in EXTRA_LABEL && it != "server" }) add(Triple(tr("Шейдеры и карты от Милли"), tr("Нет"), tr("Да")))
        if ("server" in extras) add(Triple(tr("Сборка на сервер"), tr("Нет"), tr("Да")))
        add(Triple(tr("Значок PLUS"), tr("Нет"), tr("Да")))
    }
    Block(title = tr("Без PLUS и с PLUS"), accent = tr("с PLUS"), sub = tr("бесплатная строка остаётся у всех")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Spacer(Modifier.weight(1.4f))
            Text(tr("Бесплатно"), modifier = Modifier.weight(1f), style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fgSubtle, textAlign = TextAlign.Center)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.crown, size = 14.dp, color = Rarity.LEGENDARY.color)
                Spacer(Modifier.width(4.dp))
                Text("PLUS", style = MillidaType.small.copy(fontWeight = FontWeight.Black), color = Rarity.LEGENDARY.color)
            }
        }
        rows.forEach { (label, free, plus) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .pixelSurface(colors.surface2, 2.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, modifier = Modifier.weight(1.4f), style = MillidaType.small, color = colors.fg)
                Text(free, modifier = Modifier.weight(1f), style = MillidaType.small, color = colors.fgSubtle, textAlign = TextAlign.Center)
                Text(plus, modifier = Modifier.weight(1f), style = MillidaType.small.copy(fontWeight = FontWeight.Black), color = colors.accentText, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(3.dp))
        }
    }
}

@Composable
private fun BuyBlock(
    shop: ShopState,
    active: Boolean,
    cta: @Composable () -> Unit,
    waiting: (PlusTier) -> Boolean,
    onBuy: (PlusTier) -> Unit,
    onFriends: () -> Unit,
) {
    val colors = millida
    val wide = LocalWideLayout.current
    Block(title = tr("Оформить PLUS"), accent = tr("PLUS"), sub = tr("или получить за друзей")) {
        val tierCard: @Composable (PlusTier, Modifier) -> Unit = { tier, cardModifier ->
            val diamond = tier == PlusTier.DIAMOND
            val tone = if (diamond) Rarity.RARE.color else Rarity.LEGENDARY.color
            val price = plusPriceText(shop.plus.data, tier)
            Column(
                cardModifier
                    .pixelSurface(tone.mixSrgb(colors.surface2, 0.14f), MillidaCut.plate, PixelFrame(outline = tone, outlineWidth = 2.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (diamond) "PLUS Diamond" else "PLUS", style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg)
                if (price.isNotEmpty()) Text(tr("{price} / 28 дней", price), style = MillidaType.pixel, color = tone)
                Text(if (diamond) tr("Все награды пропуска сразу") else tr("Награды каждый день"), style = MillidaType.small, color = colors.fgSubtle)
                Spacer(Modifier.height(4.dp))
                if (active) {
                    cta()
                } else {
                    val label = if (waiting(tier)) tr("Открыть оплату") else tr("Оформить")
                    if (diamond) {
                        MSecondaryButton(label, onClick = { onBuy(tier) }, modifier = Modifier.fillMaxWidth(), icon = MIcons.crown, loading = shop.isBusy("plus"))
                    } else {
                        MPrimaryButton(label, onClick = { onBuy(tier) }, modifier = Modifier.fillMaxWidth(), icon = MIcons.crown, loading = shop.isBusy("plus"))
                    }
                }
            }
        }
        val friends: @Composable (Modifier) -> Unit = { cardModifier ->
            Column(
                cardModifier
                    .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tr("За друзей"), style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg)
                Text(REFERRAL.joinToString(" · ") { (friends, days) -> tr("{friends} — {days} дн.", friends, days) }, style = MillidaType.small, color = colors.fgSubtle)
                Spacer(Modifier.height(4.dp))
                MSecondaryButton(tr("Пригласить"), onClick = onFriends, modifier = Modifier.fillMaxWidth(), icon = MIcons.users)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tierCard(PlusTier.PLUS, Modifier.weight(1f))
                tierCard(PlusTier.DIAMOND, Modifier.weight(1f))
                friends(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tierCard(PlusTier.PLUS, Modifier.fillMaxWidth())
                tierCard(PlusTier.DIAMOND, Modifier.fillMaxWidth())
                friends(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FaqBlock(price: String, milli: MilliLimits?) {
    val questions = buildList {
        add(tr("Сколько стоит?") to tr("{price} за 28 дней. Автопродление выключается на millida.net в профиле, оплаченные дни остаются.", price).trim())
        add(
            tr("Как приходят рубины и сундуки?") to
                tr("Каждый день входа открывает клетку наград в бонусе за вход: за 28 дней это {0} рубинов и {CHESTS} сундуков сверх бесплатных. Сундуки ждут в «Моих сундуках».", groupDigits(PlusPass.RUBIES), PlusPass.CHESTS),
        )
        add(tr("Что, если пропустить день?") to tr("Клетка открывается за день входа, пропуск клетку не добавляет. Забранное остаётся."))
        if (milli != null) {
            add(
                tr("Что даёт PLUS в Милли?") to
                    tr("{day} запросов в день и {0} в месяц вместо {day2} и {month}. Милли кладёт в сборку шейдеры, ресурспаки и карты и ставит её на сервер одной кнопкой.", milli.plus.day, groupDigits(milli.plus.month.toLong()), milli.free.day, milli.free.month),
            )
        }
        add(tr("Можно получить бесплатно?") to tr("Да, за друзей: первый приглашённый — 3 дня PLUS, пять друзей — ещё 14 дней, десять — 30. Друг засчитывается после двух часов игры."))
        add(tr("Что остаётся после отмены?") to tr("Рубины, сундуки, фрагменты и вещи, которые ты забрал. Гаснут клетки PLUS, осколки ×1,5, лимиты Милли и значок."))
    }
    var open by rememberSaveable { mutableIntStateOf(-1) }
    Block(title = tr("Частые вопросы"), accent = tr("вопросы")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            questions.forEachIndexed { index, (question, answer) ->
                FaqItem(question, answer, open == index) { open = if (open == index) -1 else index }
            }
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String, open: Boolean, onToggle: () -> Unit) {
    val colors = millida
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.surface2, MillidaCut.control)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(question, modifier = Modifier.weight(1f), style = MillidaType.rowTitle, color = colors.fg)
            MIcon(if (open) MIcons.chevronUp else MIcons.chevronDown, size = 16.dp, color = colors.fgSubtle)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(answer, style = MillidaType.body, color = colors.fgMuted)
        }
    }
}
