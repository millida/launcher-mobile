package net.millida.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.cabinet.Achievement
import net.millida.launcher.core.cabinet.AchievementsPage
import net.millida.launcher.core.cabinet.MILLIDA_SITE
import net.millida.launcher.core.cabinet.NewsItem
import net.millida.launcher.core.cabinet.WalletBalance
import net.millida.launcher.core.cabinet.WalletOperation
import net.millida.launcher.core.cabinet.amountText
import net.millida.launcher.core.cabinet.availableText
import net.millida.launcher.core.cabinet.kopecksAsRubles
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val OPERATIONS_SHOWN = 10
private const val AWARDS_SHOWN = 6
private const val NEWS_SHOWN = 5

/** The site keeps award pictures as numbered Minecraft blocks, 54 of them. */
private const val BLOCKS_COUNT = 54
private val BLOCK_NAME = Regex("^Block(\\d+)Millida(?:\\.png)?$", RegexOption.IGNORE_CASE)
private val DIGITS = Regex("^\\d+$")

@Composable
fun CabinetScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var allOperations by rememberSaveable { mutableStateOf(false) }
    var allAwards by rememberSaveable { mutableStateOf(false) }
    var allNews by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.millidaSignedIn) {
        if (state.millidaSignedIn) model.loadCabinet()
    }

    val refreshing = state.isLoading(Area.CABINET)
    val failure = state.errorOf(Area.CABINET)
    val loading = refreshing || (state.wallet == null && failure == null)
    val operations = state.walletHistory
    val awards = state.achievements.shownAchievements()
    val news = state.newsFeed

    MScreen(
        modifier = modifier,
        title = "Кабинет",
        subtitle = (state.portal?.nickname?.takeIf { it.isNotBlank() } ?: state.details?.nickname?.takeIf { it.isNotBlank() })
            ?.takeIf { state.millidaSignedIn },
        onBack = { model.back() },
        actions = {
            if (state.millidaSignedIn) {
                MIconButton(
                    MIcons.refresh,
                    onClick = { model.retry(Area.CABINET) },
                    enabled = !refreshing,
                    label = "Обновить",
                )
            }
        },
    ) {
        if (!state.millidaSignedIn) {
            item(key = "gate") { MSignInGate(onSignIn = model::loginMillida, title = "Кошелёк и награды — после входа") }
            return@MScreen
        }

        failure?.let { message ->
            item(key = "error") {
                MErrorCard(message, onRetry = { model.retry(Area.CABINET) }, modifier = Modifier.animateItem())
            }
        }

        val wallet = state.wallet
        if (wallet != null) {
            item(key = "wallet") {
                WalletPlate(
                    wallet = wallet,
                    onTopUp = { model.openLinkOrNotice(context, model.topUpUrl()) },
                    modifier = Modifier.animateItem(),
                )
            }
        } else if (loading) {
            item(key = "wallet-skeleton") { MSkeleton(height = 150.dp) }
        }

        item(key = "operations-head") {
            MSectionHeader(
                "Операции",
                action = if (!allOperations && operations.size > OPERATIONS_SHOWN) "Все ${operations.size}" else null,
                onAction = { allOperations = true },
            )
        }
        when {
            operations.isEmpty() && loading -> items(2, key = { "operation-skeleton:$it" }) { MSkeletonRow() }
            operations.isEmpty() && failure == null -> item(key = "operations-empty") {
                MListRow(title = "Операций пока нет", icon = MIcons.wallet)
            }
            operations.isNotEmpty() -> item(key = "operations") {
                OperationsCard(
                    operations = if (allOperations) operations else operations.take(OPERATIONS_SHOWN),
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "awards-head") {
            MSectionHeader(
                "Достижения",
                action = if (!allAwards && awards.size > AWARDS_SHOWN) "Все ${awards.size}" else null,
                onAction = { allAwards = true },
            )
        }
        val page = state.achievements
        when {
            page == null && loading -> items(2, key = { "award-skeleton:$it" }) { MSkeleton(height = 120.dp) }
            awards.isEmpty() && failure == null -> item(key = "awards-empty") {
                MListRow(title = "Достижений пока нет", icon = MIcons.trophy)
            }
        }
        if (page != null && awards.isNotEmpty()) {
            item(key = "awards-summary") { AwardsSummary(page, Modifier.animateItem()) }
            val shown = if (allAwards) awards else awards.take(AWARDS_SHOWN)
            items(shown.chunked(2), key = { pair -> "awards:" + pair.joinToString("|") { it.code.ifEmpty { it.title } } }) { pair ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { award ->
                        AwardTile(
                            award,
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item(key = "news-head") {
            MSectionHeader(
                "Новости",
                action = if (!allNews && news.size > NEWS_SHOWN) "Все ${news.size}" else null,
                onAction = { allNews = true },
            )
        }
        when {
            news.isEmpty() && loading -> items(2, key = { "news-skeleton:$it" }) { MSkeletonRow() }
            news.isEmpty() && failure == null -> item(key = "news-empty") {
                MListRow(title = "Новостей пока нет", icon = MIcons.book)
            }
        }
        items(
            if (allNews) news else news.take(NEWS_SHOWN),
            key = { "news:" + it.id.ifEmpty { it.title + it.publishedAt } },
        ) { post ->
            NewsCard(
                post = post,
                onOpen = if (post.link.isEmpty()) null else ({ model.openLinkOrNotice(context, post.link) }),
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun WalletPlate(wallet: WalletBalance, onTopUp: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .pixelSurface(colors.surface2, MillidaCut.control, colors.plateFrame()),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.wallet, size = 30.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                MSectionTitle("БАЛАНС")
                Spacer(Modifier.height(4.dp))
                Text(
                    wallet.availableText(),
                    style = MillidaType.pixelLarge,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (wallet.withdrawableKopecks > 0 || wallet.heldKopecks > 0) {
            Spacer(Modifier.height(12.dp))
            MButtonRow {
                if (wallet.withdrawableKopecks > 0) {
                    MBadge(
                        "К выводу " + wallet.withdrawableKopecks.kopecksAsRubles(),
                        color = colors.accentText,
                        background = colors.accentSoft,
                    )
                }
                if (wallet.heldKopecks > 0) {
                    MBadge(
                        "В холде " + wallet.heldKopecks.kopecksAsRubles(),
                        color = colors.warning,
                        background = colors.warningSoft,
                        icon = MIcons.clock,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        MPrimaryButton(
            "Пополнить",
            onClick = onTopUp,
            modifier = Modifier.fillMaxWidth(),
            icon = MIcons.externalLink,
        )
    }
}

@Composable
private fun OperationsCard(operations: List<WalletOperation>, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 6.dp) {
        operations.forEachIndexed { index, operation ->
            if (index > 0) MDivider()
            val detail = listOf(momentText(operation.at), operation.subtitle.ifEmpty { operation.description })
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            MListRow(
                title = operation.title.ifEmpty { operation.category.ifEmpty { "Операция" } },
                subtitle = detail.ifEmpty { null },
                icon = if (operation.incoming) MIcons.arrowDown else MIcons.arrowUp,
                plate = false,
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            operation.amountText(),
                            style = MillidaType.pixel,
                            color = if (operation.incoming) colors.accentText else colors.danger,
                            maxLines = 1,
                        )
                        if (operation.held) {
                            Spacer(Modifier.height(4.dp))
                            MBadge("Холд", color = colors.warning, background = colors.warningSoft)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AwardsSummary(page: AchievementsPage, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Получено ${page.unlocked} из ${page.total}",
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                )
                Spacer(Modifier.height(8.dp))
                MProgressBar(if (page.total > 0) page.unlocked.toFloat() / page.total.toFloat() else 0f)
            }
            Spacer(Modifier.width(12.dp))
            MBadge("${page.points} очк.", color = colors.accentText, background = colors.accentSoft, icon = MIcons.star)
        }
    }
}

@Composable
private fun AwardTile(award: Achievement, modifier: Modifier = Modifier) {
    val colors = millida
    val unlocked = award.unlocked
    MRarityFrame(
        rarity = if (unlocked) tierRarity(award.tier) else null,
        modifier = modifier,
        contentPadding = 12.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MRemoteImage(
                    url = achievementIconUrl(award),
                    icon = MIcons.trophy,
                    modifier = Modifier.alpha(if (unlocked) 1f else 0.45f),
                    size = 40.dp,
                    background = colors.inset,
                    pixelated = true,
                )
                Spacer(Modifier.weight(1f))
                if (unlocked) MIcon(MIcons.check, size = 16.dp, color = colors.accentText)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                award.title.ifEmpty { award.code },
                style = MillidaType.rowTitle.copy(fontWeight = FontWeight.ExtraBold),
                color = if (unlocked) colors.fg else colors.fgMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            when {
                award.stepped && !unlocked -> {
                    MProgressBar(award.progressRatio)
                    Spacer(Modifier.height(4.dp))
                    Text(award.progressText, style = MillidaType.pixelSmall, color = colors.fgSubtle, maxLines = 1)
                }
                unlocked -> Text(
                    momentText(award.unlockedAt).ifEmpty { "Получено" },
                    style = MillidaType.small,
                    color = colors.fgFaint,
                    maxLines = 1,
                )
                else -> Text(
                    award.description,
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NewsCard(post: NewsItem, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MRemoteImage(url = post.imageUrl, icon = MIcons.book, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    post.title.ifEmpty { post.sourceName.ifEmpty { "Новость" } },
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val source = listOf(post.sourceName, momentText(post.publishedAt)).filter { it.isNotEmpty() }.joinToString(" · ")
                if (source.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(source, style = MillidaType.small, color = colors.fgFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (post.isWipe) {
                Spacer(Modifier.width(8.dp))
                MBadge("Вайп", color = colors.warning, background = colors.warningSoft)
            }
        }
        if (post.body.isNotEmpty() && post.body != post.title) {
            Spacer(Modifier.height(10.dp))
            Text(
                post.body,
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A hidden award the player has not taken is not a teaser: it is not listed. */
private fun AchievementsPage?.shownAchievements(): List<Achievement> {
    val page = this ?: return emptyList()
    val visible = page.items.filter { !it.hidden || it.unlocked }
    return visible.filter { it.unlocked } + visible.filterNot { it.unlocked }
}

private fun tierRarity(tier: String): Rarity? = when (tier.trim().lowercase()) {
    "bronze" -> Rarity.UNCOMMON
    "silver" -> Rarity.RARE
    "gold" -> Rarity.LEGENDARY
    "legendary" -> Rarity.MYTHIC
    else -> Rarity.of(tier)
}

/** The same picture the site picks: a path as is, a block by name or number, or a block chosen by the code. */
private fun achievementIconUrl(award: Achievement): String {
    val raw = award.icon.trim()
    if (raw.startsWith("https://")) return raw
    if (raw.startsWith("/")) return MILLIDA_SITE + raw
    BLOCK_NAME.find(raw)?.let { return "$MILLIDA_SITE/blocks/Block${it.groupValues[1]}Millida.png" }
    if (DIGITS.matches(raw)) {
        val number = raw.toIntOrNull()?.coerceIn(1, BLOCKS_COUNT) ?: 1
        return "$MILLIDA_SITE/blocks/Block${number}Millida.png"
    }
    var hash = 0L
    for (char in award.code) hash = (hash * 31 + char.code) and 0xFFFFFFFFL
    return "$MILLIDA_SITE/blocks/Block${hash % BLOCKS_COUNT + 1}Millida.png"
}

private val cabinetMoment: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))

/** The server sends several shapes of ISO date; an unparsed one still shows. */
private fun momentText(raw: String): String {
    if (raw.isBlank()) return ""
    val instant = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return raw.take(10)
    return cabinetMoment.format(instant.atZone(ZoneId.systemDefault()))
}
