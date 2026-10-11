package net.millida.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.portal.asRubles
import net.millida.launcher.ui.media.shotsCount
import net.millida.launcher.ui.settings.stepIn

private val HERO_HEIGHT = 108.dp
private val HERO_OVERFLOW = 22.dp
private val HUB_GAP = 12.dp

private class HubEntry(
    val title: String,
    val subtitle: String,
    val icon: MillidaIcon,
    val screen: Screen,
    val badge: Int = 0,
    val alert: Boolean = false,
)

@Composable
fun MoreScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val colors = millida

    LaunchedEffect(state.millidaSignedIn) {
        val idle = !state.isLoading(Area.FRIENDS) && state.errorOf(Area.FRIENDS) == null
        if (state.millidaSignedIn && state.friends.isEmpty() && idle) model.loadFriends()
    }
    LaunchedEffect(Unit) { model.loadShotsCount() }

    val friends = HubEntry(
        title = "Друзья",
        subtitle = friendsLine(state),
        icon = MIcons.users,
        screen = Screen.FRIENDS,
        badge = state.friendsBadge,
        alert = state.incomingRequests > 0,
    )
    val servers = HubEntry(
        title = "Серверы",
        subtitle = if (state.serversTotal > 0) "Серверов: ${state.serversTotal}" else "Топ серверов",
        icon = MIcons.map,
        screen = Screen.SERVERS,
    )
    val hosting = HubEntry(
        title = "Хостинг",
        subtitle = if (state.hosted.isNotEmpty()) "Моих: ${state.hosted.size}" else "Свой сервер",
        icon = MIcons.server,
        screen = Screen.HOSTING,
    )
    val cabinet = HubEntry(
        title = "Кабинет",
        subtitle = "Награды и новости",
        icon = MIcons.trophy,
        screen = Screen.CABINET,
    )
    val packs = HubEntry(
        title = "Коды сборок",
        subtitle = if (state.myPacks.isNotEmpty()) "Моих: ${state.myPacks.size}" else "По коду или файлом",
        icon = MIcons.chest,
        screen = Screen.PACKS,
    )
    val shots = HubEntry(
        title = "Скриншоты",
        subtitle = state.shotsAll?.takeIf { it > 0 }?.let(::shotsCount) ?: "Из игры",
        icon = MIcons.image,
        screen = Screen.SCREENSHOTS,
    )
    val bedrock = HubEntry(
        title = "Bedrock",
        subtitle = if (state.bedrockBridgeReady || state.bedrockOfficialReady) "Готов к игре" else "Порт 19132",
        icon = MIcons.blocks,
        screen = Screen.BEDROCK,
    )
    val settings = HubEntry(
        title = "Настройки",
        subtitle = "Звук, тема, память",
        icon = MIcons.settings,
        screen = Screen.SETTINGS,
    )
    val open: (HubEntry) -> Unit = { entry -> model.open(entry.screen) }

    MScreen(modifier = modifier, title = "Ещё", spacing = HUB_GAP) {
        item(key = "more-account") {
            AccountPlate(state, onOpen = { model.open(Screen.ACCOUNT) }, modifier = stepIn())
        }
        item(key = "more-social") {
            Row(stepIn(), horizontalArrangement = Arrangement.spacedBy(HUB_GAP)) {
                HeroTile(friends, MHeroTone.Wardrobe, open, Modifier.weight(1f))
                HeroTile(servers, MHeroTone.Daily, open, Modifier.weight(1f))
            }
        }
        item(key = "more-owned") {
            Row(stepIn(), horizontalArrangement = Arrangement.spacedBy(HUB_GAP)) {
                HeroTile(hosting, MHeroTone.server(colors.accent), open, Modifier.weight(1f))
                HeroTile(cabinet, MHeroTone.Shop, open, Modifier.weight(1f))
            }
        }
        item(key = "more-tools") {
            Row(stepIn(), horizontalArrangement = Arrangement.spacedBy(HUB_GAP)) {
                SlotTile(packs, open, Modifier.weight(1f))
                SlotTile(shots, open, Modifier.weight(1f))
            }
        }
        item(key = "more-app") {
            Row(stepIn(), horizontalArrangement = Arrangement.spacedBy(HUB_GAP)) {
                SlotTile(bedrock, open, Modifier.weight(1f))
                SlotTile(settings, open, Modifier.weight(1f))
            }
        }
        item(key = "more-premium") {
            SlotTile(
                HubEntry(title = "Премиум", subtitle = "Платные сборки", icon = MIcons.crown, screen = Screen.PREMIUM),
                open,
                stepIn(),
            )
        }
        item(key = "more-version") {
            Text(
                "Millida Mobile ${state.appVersion}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                style = MillidaType.pixelSmall,
                color = colors.fgFaint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun friendsLine(state: UiState): String {
    val online = state.friends.count { it.online || it.playing }
    return when {
        !state.millidaSignedIn -> "Нужен вход"
        state.incomingRequests > 0 -> "Заявки: ${state.incomingRequests}"
        state.unreadMessages > 0 -> "Новые: ${state.unreadMessages}"
        online > 0 -> "В сети: $online"
        state.friends.isNotEmpty() -> "Друзей: ${state.friends.size}"
        else -> "Позови в игру"
    }
}

@Composable
private fun AccountPlate(state: UiState, onOpen: () -> Unit, modifier: Modifier) {
    val colors = millida
    val portal = state.portal
    val account = state.account
    val nick = portal?.nickname?.takeIf { it.isNotBlank() } ?: account?.nick?.takeIf { it.isNotBlank() }
    val avatar = portal?.avatarUrl?.takeIf { it.isNotBlank() } ?: nick?.let { headAvatarUrl(it) }
    val expired = state.sessionExpired != null && state.sessionExpired == account?.kind

    MCard(modifier = modifier, onClick = onOpen, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MAvatar(url = avatar, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    nick ?: "Гость",
                    style = MillidaType.pixel.copy(fontSize = 17.sp, lineHeight = 20.sp),
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    expired -> PlateLine("Вход истёк — войди заново", colors.danger)
                    portal != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        MIcon(MIcons.wallet, size = 14.dp)
                        Spacer(Modifier.width(6.dp))
                        PlateLine(portal.balanceKopecks.asRubles(), colors.fgSubtle)
                    }
                    account != null -> PlateLine(kindLine(account.kind), colors.fgSubtle)
                    else -> PlateLine("Войди или добавь аккаунт", colors.accentText)
                }
            }
            Spacer(Modifier.width(10.dp))
            MIcon(MIcons.chevronRight, size = 16.dp, color = colors.fgFaint)
        }
    }
}

@Composable
private fun PlateLine(text: String, color: Color) {
    Text(
        text,
        style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun kindLine(kind: AccountKind): String = when (kind) {
    AccountKind.MILLIDA -> "Аккаунт Millida"
    AccountKind.MICROSOFT -> "Лицензия Microsoft"
    AccountKind.OFFLINE -> "Игра без входа"
}

@Composable
private fun HeroTile(entry: HubEntry, tone: MHeroTone, onOpen: (HubEntry) -> Unit, modifier: Modifier) {
    Box(modifier) {
        MHeroCard(
            title = entry.title,
            subtitle = entry.subtitle,
            tone = tone,
            height = HERO_HEIGHT,
            overflow = HERO_OVERFLOW,
            alert = entry.alert,
            onClick = { onOpen(entry) },
            art = {
                MIcon(
                    entry.icon,
                    size = 60.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 14.dp),
                )
            },
        )
        MCounterBadge(
            entry.badge,
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = HERO_OVERFLOW - 8.dp),
        )
    }
}

@Composable
private fun SlotTile(entry: HubEntry, onOpen: (HubEntry) -> Unit, modifier: Modifier) {
    val colors = millida
    MCard(modifier = modifier, onClick = { onOpen(entry) }, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(entry.icon, size = 36.dp)
            Spacer(Modifier.weight(1f))
            MIcon(MIcons.chevronRight, size = 14.dp, color = colors.fgFaint)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            entry.title,
            style = MillidaType.rowTitle,
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.subtitle,
            style = MillidaType.small,
            color = colors.fgSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
