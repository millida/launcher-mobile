package net.millida.launcher.ui.social

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCounterBadge
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton

/** How many friends stand next to the character; the rest are one tap away in «Друзья». */
private const val PARTY_SHOWN = 4

private const val PROFILE_BASE = "https://millida.net/u/"

/**
 * The lobby party, as on the desktop (src/components/lobby/LobbyParty.tsx):
 * friends online next to the character, "Зайти" to the one already on a server
 * and "Позвать" to the rest. Nobody online - the player copies their profile
 * link instead. The last row is the way into «Друзья» with every unread count.
 *
 * [modeTitle] is what is picked to play today; it goes into the call text.
 */
@Composable
fun LobbyParty(state: UiState, model: LauncherViewModel, modeTitle: String?, modifier: Modifier = Modifier) {
    if (!state.millidaSignedIn) return
    val social by model.social.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { model.loadFriends() }

    val online = state.friends
        .filter { it.online }
        .sortedByDescending { it.joinAddress() != null }
        .take(PARTY_SHOWN)

    fun invite() {
        val slug = state.details?.publicSlug?.trim().orEmpty()
        if (slug.isEmpty()) {
            model.open(Screen.FRIENDS)
            return
        }
        clipboard.setText(AnnotatedString(PROFILE_BASE + java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20")))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) model.notice(tr("Ссылка скопирована — отправь другу"))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (online.isEmpty()) {
            MListRow(
                title = tr("Никого нет в сети"),
                icon = MIcons.users,
                trailing = {
                    MPrimaryButton(tr("Пригласить"), onClick = ::invite, icon = MIcons.link, size = MButtonSize.Small)
                },
            )
        }
        online.forEach { friend -> PartyMate(friend, friend.userId in social.called, model, modeTitle) }
        MListRow(
            title = if (online.isEmpty()) tr("Друзья") else tr("Все друзья"),
            icon = MIcons.users,
            onClick = { model.open(Screen.FRIENDS) },
            badge = state.friendsBadge,
        )
    }
}

@Composable
private fun PartyMate(friend: Friend, called: Boolean, model: LauncherViewModel, modeTitle: String?) {
    val address = friend.joinAddress()
    val status = when {
        address != null -> friend.serverName?.takeIf { it.isNotBlank() } ?: tr("В игре")
        friend.place == FriendPlace.GAME -> tr("В игре")
        else -> tr("В лаунчере")
    }
    MListRow(
        title = friend.shownName(),
        subtitle = status,
        leading = {
            Box {
                MAvatar(url = avatarFor(friend.shownName(), friend.avatarUrl), size = 36.dp, presence = friend.presence())
                MCounterBadge(friend.unread, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
            }
        },
        trailing = {
            if (address != null) {
                MPrimaryButton(
                    tr("Зайти"),
                    onClick = {
                        model.playOnAddress(address, friend.serverName?.takeIf { it.isNotBlank() } ?: tr("Сервер {0}", friend.shownName()))
                    },
                    icon = MIcons.login,
                    size = MButtonSize.Small,
                )
            } else {
                MSecondaryButton(
                    if (called) tr("Позвали") else tr("Позвать"),
                    onClick = { model.social.lobbyCall(friend, modeTitle) },
                    enabled = !called,
                    icon = if (called) MIcons.check else MIcons.send,
                    size = MButtonSize.Small,
                )
            }
        },
    )
}
