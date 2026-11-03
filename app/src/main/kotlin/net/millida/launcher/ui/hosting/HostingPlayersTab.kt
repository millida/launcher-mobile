package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import net.millida.launcher.HostingModel
import net.millida.launcher.HostingPanel
import net.millida.launcher.core.hosting.HOSTING_ROSTER_ROLES
import net.millida.launcher.core.hosting.HostingPlayer
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPresence
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.headAvatarUrl
import net.millida.launcher.ui.hostingDay
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.screenPadding

private data class OnlineAction(
    val id: String,
    val label: String,
    val icon: MillidaIcon,
    val done: (String) -> String,
)

private val ONLINE_ACTIONS: List<OnlineAction> get() = listOf(
    OnlineAction("op", tr("Выдать права оператора"), MIcons.key) { tr("{it} теперь оператор", it) },
    OnlineAction("creative", tr("Творческий"), MIcons.brush) { tr("{it} в творческом режиме", it) },
    OnlineAction("survival", tr("Выживание"), MIcons.hammer) { tr("{it} в режиме выживания", it) },
    OnlineAction("heal", tr("Лечить"), MIcons.heart) { tr("{it} вылечен", it) },
    OnlineAction("spawn", tr("На спавн"), MIcons.map) { tr("{it} отправлен на спавн", it) },
    OnlineAction("kick", tr("Кикнуть"), MIcons.logout) { tr("{it} кикнут", it) },
    OnlineAction(BAN_ACTION, tr("Забанить"), MIcons.ban) { tr("{it} забанен", it) },
)

private const val BAN_ACTION = "ban"
private const val OWNER_ROLE = "OWNER"

@Composable
fun HostingPlayersTab(
    serverId: String,
    running: Boolean,
    panel: HostingPanel,
    hosting: HostingModel,
    roster: List<HostingPlayer>,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    var picked by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var banAsk by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var memberId by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var removeAsk by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var nick by rememberSaveable(serverId) { mutableStateOf("") }
    val online = panel.online
    val busy = panel.rosterBusy != null
    val team = roster.map { it.copy(role = it.role.uppercase()) }

    DisposableEffect(serverId, visible) {
        if (visible && serverId.isNotEmpty()) hosting.startLive(serverId)
        onDispose { hosting.stopLive() }
    }

    val add: () -> Unit = {
        if (nick.isNotBlank() && !busy) hosting.addRosterPlayer(serverId, nick) { nick = "" }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = screenPadding, end = screenPadding, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "online") {
            MCard(contentPadding = 14.dp) {
                Text(
                    tr("Сейчас на сервере — {0}", if (running) online?.size ?: 0 else 0),
                    style = MillidaType.sectionTitle.copy(fontSize = 16.sp),
                    color = colors.fg,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    !running -> MEmptyState(icon = MIcons.power, title = tr("Сервер выключен"))
                    online == null && panel.onlineError -> MEmptyState(icon = MIcons.users, title = tr("Список игроков недоступен"))
                    online == null -> repeat(2) {
                        MSkeleton(height = 44.dp)
                        Spacer(Modifier.height(6.dp))
                    }
                    online.isEmpty() -> MEmptyState(icon = MIcons.users, title = tr("Никого нет в игре"))
                    else -> online.forEachIndexed { index, entry ->
                        if (index > 0) MDivider()
                        MListRow(
                            title = entry.name,
                            plate = false,
                            leading = { MAvatar(headAvatarUrl(entry.name), size = 36.dp, presence = MPresence.Playing) },
                            onClick = { picked = entry.name },
                        )
                    }
                }
            }
        }
        item(key = "team") {
            MCard(contentPadding = 14.dp) {
                Text(tr("Команда сервера"), style = MillidaType.sectionTitle.copy(fontSize = 16.sp), color = colors.fg)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MField(
                        value = nick,
                        onValueChange = { nick = it.take(16) },
                        placeholder = tr("Ник в Minecraft"),
                        modifier = Modifier.weight(1f),
                        imeAction = ImeAction.Done,
                        onImeAction = add,
                        autoCorrectEnabled = false,
                    )
                    Spacer(Modifier.width(8.dp))
                    MPrimaryButton(
                        tr("Добавить"),
                        onClick = add,
                        enabled = nick.isNotBlank() && !busy,
                        icon = MIcons.plus,
                        size = MButtonSize.Small,
                        loading = panel.rosterBusy == "add",
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (team.isEmpty()) {
                    MEmptyState(icon = MIcons.users, title = tr("Пока никого"))
                } else {
                    team.forEachIndexed { index, member ->
                        if (index > 0) MDivider()
                        val name = member.nickname.ifEmpty { member.id }
                        MListRow(
                            title = name,
                            subtitle = memberLine(member),
                            plate = false,
                            leading = { MAvatar(headAvatarUrl(name), size = 32.dp) },
                            trailing = {
                                if (member.banned) {
                                    MBadge(tr("забанен"), colors.danger, colors.dangerSoft)
                                } else {
                                    MBadge(roleLabel(member.role), colors.fgSubtle, colors.surface2)
                                }
                            },
                            onClick = if (member.role == OWNER_ROLE) null else ({ memberId = member.id }),
                        )
                    }
                }
            }
        }
    }

    picked?.let { name ->
        MBottomSheet(onDismiss = { picked = null }, title = name) {
            ONLINE_ACTIONS.forEachIndexed { index, action ->
                if (index > 0) MDivider()
                MListRow(
                    title = action.label,
                    icon = action.icon,
                    plate = false,
                    onClick = {
                        picked = null
                        if (action.id == BAN_ACTION) {
                            banAsk = name
                        } else {
                            hosting.playerAction(serverId, name, action.id, action.done(name))
                        }
                    },
                )
            }
        }
    }

    banAsk?.let { name ->
        MConfirmDialog(
            title = tr("Забанить {name}?", name),
            text = tr("Игрок не сможет зайти, пока бан не снимут."),
            confirm = tr("Забанить"),
            onConfirm = {
                banAsk = null
                hosting.playerAction(serverId, name, BAN_ACTION, tr("{name} забанен", name))
            },
            onDismiss = { banAsk = null },
        )
    }

    team.firstOrNull { it.id == memberId }?.let { member ->
        val name = member.nickname.ifEmpty { member.id }
        MBottomSheet(onDismiss = { memberId = null }, title = name) {
            Text(tr("Роль"), style = MillidaType.small, color = colors.fgMuted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HOSTING_ROSTER_ROLES.forEach { role ->
                    MChip(
                        text = roleTitle(role),
                        selected = member.role == role,
                        enabled = !busy,
                        onClick = { if (member.role != role) hosting.changeRosterRole(serverId, member.id, role) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            MListRow(
                title = if (member.banned) tr("Разбанить") else tr("Забанить"),
                icon = if (member.banned) MIcons.check else MIcons.ban,
                plate = false,
                onClick = {
                    memberId = null
                    hosting.banRosterPlayer(serverId, member.id, !member.banned)
                },
            )
            MDivider()
            MListRow(
                title = tr("Убрать из команды"),
                icon = MIcons.trash,
                plate = false,
                onClick = {
                    memberId = null
                    removeAsk = member.id
                },
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    team.firstOrNull { it.id == removeAsk }?.let { member ->
        val name = member.nickname.ifEmpty { member.id }
        MConfirmDialog(
            title = tr("Убрать {name} из команды сервера?", name),
            text = null,
            confirm = tr("Убрать"),
            onConfirm = {
                removeAsk = null
                hosting.removeRosterPlayer(serverId, member.id)
            },
            onDismiss = { removeAsk = null },
        )
    }
}

private fun memberLine(member: HostingPlayer): String? {
    val day = member.lastJoinAt.takeIf { it.isNotEmpty() }?.let(::hostingDay).orEmpty()
    return if (day.isEmpty()) null else tr("заходил {day}", day)
}

private fun roleTitle(role: String): String = when (role) {
    "ADMIN" -> tr("Админ")
    "MODERATOR" -> tr("Модератор")
    "PLAYER" -> tr("Игрок")
    else -> role
}

private fun roleLabel(role: String): String = when (role) {
    OWNER_ROLE -> tr("владелец")
    "ADMIN" -> tr("админ")
    "MODERATOR" -> tr("модератор")
    "PLAYER" -> tr("игрок")
    else -> role.lowercase()
}
