package net.millida.launcher.ui.social

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import net.millida.launcher.core.friends.FriendProfileCard
import net.millida.launcher.core.friends.FriendProfileStats
import net.millida.launcher.core.friends.ServerInvites
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface

private const val BODY_RENDER = "https://api.millida.net/v2/heads/body/"

/**
 * "Позвать играть" of the conversation header (InvitePlay in the desktop
 * Chat.tsx): own servers first; with none of them, a row to create one, and
 * the invite leaves by itself once it runs; any address can be typed below.
 */
@Composable
internal fun InvitePlaySheet(
    servers: List<HostingServerState>?,
    onServer: (HostingServerState) -> Unit,
    onCreate: () -> Unit,
    onAddress: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    var address by rememberSaveable { mutableStateOf("") }
    val mine = servers.orEmpty().filter { it.address.isNotBlank() }
    MBottomSheet(onDismiss = onDismiss, title = "Позвать играть") {
        mine.forEach { server ->
            MListRow(
                ServerInvites.serverTitle(server),
                icon = MIcons.server,
                onClick = { onServer(server) },
                plate = false,
                trailing = { Text("Мой сервер", style = MillidaType.small, color = colors.accentText, maxLines = 1) },
            )
        }
        if (servers != null && mine.isEmpty()) {
            MListRow(
                "Создать свой сервер",
                icon = MIcons.plus,
                onClick = onCreate,
                plate = false,
                trailing = {},
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                MField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = "Адрес сервера",
                    imeAction = ImeAction.Send,
                    onImeAction = { if (ServerInvites.isServerAddr(address)) onAddress(address.trim()) },
                    autoCorrectEnabled = false,
                )
            }
            Spacer(Modifier.width(8.dp))
            MSecondaryButton(
                "Позвать",
                onClick = { onAddress(address.trim()) },
                enabled = ServerInvites.isServerAddr(address),
                size = MButtonSize.Small,
            )
        }
    }
}

/**
 * A friend's card above the conversation, the desktop `.chat-prof`: the skin
 * in full height, the nick, the status line and the stats block.
 */
@Composable
internal fun FriendProfileHeader(
    nick: String,
    status: String,
    card: FriendProfileCard?,
    onJoin: (FriendProfileCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SubcomposeAsyncImage(
            model = BODY_RENDER + Uri.encode(nick.ifBlank { "Steve" }) + "?size=128",
            contentDescription = null,
            modifier = Modifier.height(128.dp),
            contentScale = ContentScale.FillHeight,
            filterQuality = FilterQuality.None,
            loading = { MSkeleton(height = 128.dp, modifier = Modifier.width(64.dp)) },
            error = { MIcon(MIcons.user, size = 48.dp, color = colors.fgFaint) },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(nick, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card == null) {
            MSkeleton(height = 72.dp)
        } else {
            ProfileStats(card, onJoin)
        }
    }
}

@Composable
private fun ProfileStats(card: FriendProfileCard, onJoin: (FriendProfileCard) -> Unit) {
    val colors = millida
    val rows = FriendProfileStats.rows(card, System.currentTimeMillis() / 1000)
    val joinable = FriendProfileStats.joinable(card)
    if (rows.isEmpty() && !joinable) {
        Text("Пока без игр", style = MillidaType.small, color = colors.fgFaint, modifier = Modifier.padding(top = 8.dp))
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.inset, MillidaCut.control)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (joinable) {
            MPrimaryButton(
                "Зайти",
                onClick = { onJoin(card) },
                icon = MIcons.login,
                size = MButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        rows.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(label, modifier = Modifier.weight(1f), style = MillidaType.small, color = colors.fgSubtle)
                Spacer(Modifier.size(12.dp))
                Text(
                    value,
                    style = MillidaType.small.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.fg,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
