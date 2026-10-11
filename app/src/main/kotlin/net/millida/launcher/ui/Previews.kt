package net.millida.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.millida.launcher.UiState
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.mojang.VersionManifest
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.rating.RatedServer

/**
 * The shapes the launcher actually breaks on: a 1080x2400 phone at 395 dpi is
 * 360 dp wide, an older Samsung is 320, and a player with poor eyesight runs
 * the system font at 1.3. Every preview here is one of those three.
 */
@Preview(name = "360 dp", widthDp = 360, showBackground = true)
@Preview(name = "320 dp", widthDp = 320, showBackground = true)
@Preview(name = "360 dp, крупный шрифт", widthDp = 360, fontScale = 1.3f, showBackground = true)
private annotation class NarrowPreviews

@Composable
private fun PreviewFrame(content: @Composable ColumnScope.() -> Unit) {
    MillidaTheme(dark = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(millida.bg)
                .verticalScroll(rememberScrollState())
                .padding(screenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

private val busyServer = RatedServer(
    name = "Выживание с модами и квестами",
    slug = "survival",
    ip = "play.millida.net",
    online = 1284,
    up = true,
    shortDesc = "Технический сервер с квестами, экономикой и защитой территорий.",
    license = "CRACKED",
    logoUrl = null,
    categories = listOf("TECHNIC"),
    versionMajors = listOf("26.2", "26.1", "26.2", ""),
)

private val singleVersionServer = busyServer.copy(
    name = "Анархия без правил",
    ip = "anarchy.millida.net",
    up = false,
    license = "LICENSE",
    categories = listOf("ANARCHY"),
    versionMajors = listOf("1.21.4"),
)

@NarrowPreviews
@Composable
private fun ServerCardPreview() = PreviewFrame {
    ServerCard(server = busyServer, onCopy = {}, onPlay = {})
    ServerCard(server = singleVersionServer, onCopy = {}, onPlay = {})
}

@NarrowPreviews
@Composable
private fun CatalogCardPreview() = PreviewFrame {
    CatalogCard(
        item = CatalogItem(
            projectId = "sodium",
            title = "Sodium — ускорение отрисовки",
            description = "Переписанный движок отрисовки: больше кадров и меньше нагрузка на телефон.",
            downloads = 42_000_000,
            source = CatalogSource.CURSEFORGE,
        ),
        busy = false,
        onInstall = {},
    )
}

@NarrowPreviews
@Composable
private fun HostedCardPreview() = PreviewFrame {
    HostedCard(
        server = HostingServerState(
            id = "srv-1",
            name = "Сервер друзей",
            status = "RUNNING",
            address = "friends.millida.net:25565",
            core = "Paper",
            version = "1.21.1",
            planName = "Продвинутый 8 ГБ",
            planRamMb = 8192,
            maxPlayers = 40,
            playersOnline = 7,
            planPriceKopecks = 59_900,
        ),
        onCopy = {},
        onPlay = {},
    )
}

@NarrowPreviews
@Composable
private fun FriendCardPreview() = PreviewFrame {
    FriendCard(
        friend = Friend(
            userId = "1",
            nickname = "Alex",
            displayName = "Alex",
            online = true,
            playing = true,
            place = FriendPlace.GAME,
            serverName = "Выживание с модами и квестами",
            build = "Фабрик 1.21.4",
            gameNick = "Steve_1234",
            unread = 12,
            serverIp = "play.millida.net",
        ),
        hours = 45_000L,
        busy = false,
        onWrite = {},
        onJoin = {},
        onMenu = {},
    )
}

@NarrowPreviews
@Composable
private fun AccountEntryPreview() = PreviewFrame {
    ScreenTitle("Аккаунт", "Вход не выполнен")
    AccountEntrySection(
        offlineNick = "",
        onOfflineNick = {},
        onMillida = {},
        onMicrosoft = {},
        onOffline = {},
    )
}

@NarrowPreviews
@Composable
private fun CreateProfileSheetPreview() = PreviewFrame {
    CreateProfileSheet(
        state = UiState(
            versions = listOf("1.21.4", "1.21.1", "1.20.6", "1.20.1", "1.16.5")
                .map { VersionManifest.ManifestEntry(id = it, url = "") },
        ),
        onDismiss = {},
        onCreate = { _, _, _ -> },
    )
}

/** Three actions is the widest a dialog ever gets, and the widest it survives. */
@NarrowPreviews
@Composable
private fun DialogActionsPreview() = PreviewFrame {
    MDialog(
        title = "Подтверди вход",
        onDismiss = {},
        body = { Text("Страница уже открывается, код скопирован.", color = millida.fgSubtle) },
        actions = {
            MSecondaryButton("Скопировать", onClick = {}, icon = MIcons.copy, size = MButtonSize.Small)
            MPrimaryButton("Открыть страницу", onClick = {})
            MTextButton("Отменить", onClick = {}, color = millida.danger)
        },
    )
}

private val galleryRarities = listOf(Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY, Rarity.MYTHIC)

/** Every kit part on one page, in the theme given: the page a screenshot test would pin. */
@Composable
private fun KitGallery(dark: Boolean) {
    MillidaTheme(dark = dark) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(millida.bg),
        ) {
            MTopBar(title = "Настройки", onBack = {}, subtitle = "Звук и вид")
            Column(
                Modifier.padding(screenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenTitle("Кит Millida", "Все детали на одной странице")
                MSectionHeader("Кнопки", action = "Все", onAction = {})
                MButtonRow {
                    MPrimaryButton("Играть", onClick = {}, icon = MIcons.play)
                    MSecondaryButton("Сборки", onClick = {}, icon = MIcons.library)
                    MSecondaryButton("Скопировать", onClick = {}, icon = MIcons.copy, size = MButtonSize.Small)
                    MDangerButton("Удалить", onClick = {}, icon = MIcons.menuTrash)
                    MTextButton("Отмена", onClick = {})
                }
                MButtonRow {
                    MButton("Ставим", onClick = {}, variant = MButtonVariant.Primary, loading = true)
                    MButton("Нельзя", onClick = {}, variant = MButtonVariant.Primary, enabled = false)
                    MButton("Играет", onClick = {}, variant = MButtonVariant.Running, icon = MIcons.pause)
                }
                MPrimaryButton("Играть", onClick = {}, size = MButtonSize.Large, icon = MIcons.play)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MBackButton(onClick = {})
                    MIconButton(MIcons.settings, onClick = {}, label = "Настройки")
                    MIconButton(MIcons.chat, onClick = {}, badge = 3, label = "Сообщения")
                    MIconButton(MIcons.refresh, onClick = {}, enabled = false, label = "Обновить")
                }
                MButtonRow {
                    MChip("Моды", selected = true, onClick = {}, count = 12)
                    MChip("Шейдеры", selected = false, onClick = {}, icon = MIcons.sun)
                    MChip("Ресурсы", selected = false, onClick = {})
                }
                MSegmented(
                    options = listOf("Друзья", "Чаты", "Заявки"),
                    selected = "Друзья",
                    onSelect = {},
                    badge = { if (it == "Заявки") 2 else 0 },
                )
                MTabs(
                    tabs = listOf("Библиотека", "Ресурсы"),
                    selected = "Библиотека",
                    onSelect = {},
                    icon = { if (it == "Библиотека") MIcons.library else MIcons.catalog },
                )
                MField(value = "", onValueChange = {}, placeholder = "Ник друга", leadingIcon = MIcons.search)
                MField(value = "Alex", onValueChange = {}, placeholder = "Ник", clearable = true)
                MCard {
                    MSectionTitle("ЗВУК")
                    MSwitchRow("Звуки кнопок", "Как в игре", true) {}
                    MSwitchRow("Вибрация", null, false) {}
                    MSlider(value = 0.6f, onValueChange = {}, steps = 4)
                }
                MProgressBar(0.62f)
                MProgressBar(null)
                MBusyCard("Ставим сборку", 0.4f, note = "Fabric 1.21.4")
                MErrorCard("Нет сети — проверь подключение и нажми «Повторить»", onRetry = {})
                MSkeleton(56.dp)
                MSkeletonRow()
                MListRow(title = "Друзья", subtitle = "Трое в сети", icon = MIcons.friends, onClick = {}, badge = 2)
                MCard(contentPadding = 6.dp) {
                    MListRow(title = "Настройки", icon = MIcons.settings, onClick = {}, plate = false)
                    MDivider()
                    MListRow(
                        title = "Alex",
                        subtitle = "Играет на сервере",
                        plate = false,
                        leading = { MAvatar(url = null, presence = MPresence.Playing, plus = true) },
                        trailing = { MRubyAmount(12500) },
                    )
                }
                MCard {
                    MInfoRow("Адрес", "play.millida.net", mono = true)
                    MInfoRow("Тариф", "Продвинутый 8 ГБ")
                }
                MButtonRow {
                    MBadge("Онлайн", color = millida.accentText, background = millida.accentSoft)
                    MBadge("Ошибка", color = millida.danger, background = millida.dangerSoft, icon = MIcons.alert)
                    MCounterBadge(7)
                    MRubyAmount(250, plate = true)
                }
                MButtonRow {
                    Rarity.entries.forEach { MRarityPlate(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    galleryRarities.forEach { rarity ->
                        MRarityFrame(rarity, Modifier.size(78.dp), selected = rarity == Rarity.RARE, onClick = {}) {
                            MIcon(MIcons.cape, size = 36.dp, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MHeroCard(
                        title = "Магазин",
                        modifier = Modifier.weight(1f),
                        subtitle = "Рубины и сундуки",
                        tone = MHeroTone.Shop,
                        alert = true,
                        onClick = {},
                    ) {
                        Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 30.dp)) {
                            MIcon(MIcons.chest, size = 72.dp)
                        }
                    }
                    MHeroCard(
                        title = "Мой скин",
                        modifier = Modifier.weight(1f),
                        subtitle = "Гардероб",
                        tone = MHeroTone.Wardrobe,
                        ribbon = "Новое",
                        onClick = {},
                    )
                }
                MEmptyState(MIcons.inbox, "Заявок нет", actionText = "Найти друзей", onAction = {})
                MSignInGate(onSignIn = {})
            }
        }
    }
}

@Preview(name = "Кит, тёмная", widthDp = 390, heightDp = 3000, showBackground = true)
@Composable
private fun KitGalleryDarkPreview() = KitGallery(dark = true)

@Preview(name = "Кит, светлая", widthDp = 390, heightDp = 3000, showBackground = true)
@Composable
private fun KitGalleryLightPreview() = KitGallery(dark = false)

@Preview(name = "Кит, 320 dp", widthDp = 320, heightDp = 3000, showBackground = true)
@Composable
private fun KitGalleryNarrowPreview() = KitGallery(dark = true)
