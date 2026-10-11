package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.core.hosting.HOSTING_PERMISSIONS
import net.millida.launcher.core.hosting.HostingApiKey
import net.millida.launcher.core.hosting.HostingSftp
import net.millida.launcher.core.hosting.HostingShare
import net.millida.launcher.forServer
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

private val ALL_PERMISSIONS: Set<String> = HOSTING_PERMISSIONS.map { it.first }.toSet()

private val ROLE_PRESETS: List<Pair<String, Set<String>>> = listOf(
    "Модератор" to setOf("console", "players"),
    "Помощник" to setOf("control", "console", "players", "world", "backups"),
    "Админ" to ALL_PERMISSIONS,
)

private val DEFAULT_SHARE: Set<String> = setOf("control", "console")

private sealed interface ShareEdit {
    data object New : ShareEdit

    data class Existing(val share: HostingShare) : ShareEdit
}

@Composable
fun HostingAccessTab(serverId: String, tools: HostingToolsModel) {
    LaunchedEffect(serverId) {
        tools.ensureRights(serverId)
        tools.openAccess(serverId)
    }
    val all by tools.access.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    val state = all.takeIf { it.serverId == serverId }
    val owner = access?.owner ?: true
    val canFiles = access?.can("files") ?: true
    val full = access?.fullAccess ?: true
    val copy = rememberToolsCopy(tools)
    val copySecret = rememberToolsSecretCopy(tools)

    var shareEdit by remember { mutableStateOf<ShareEdit?>(null) }
    var keySheet by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<HostingApiKey?>(null) }
    var removing by remember { mutableStateOf<HostingShare?>(null) }
    var closingSftp by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf<String?>(null) }

    ToolsList {
        state?.error?.let { error ->
            item(key = "error") { MErrorCard(error, onRetry = { tools.loadAccess(serverId) }) }
        }
        if (owner) {
            item(key = "shares-head") {
                ToolsHead(
                    title = "Кому открыт сервер",
                    subtitle = state?.shares?.takeIf { it.isNotEmpty() }?.let { "Людей: ${it.size}" },
                    action = {
                        MPrimaryButton(
                            "Дать доступ",
                            onClick = { shareEdit = ShareEdit.New },
                            icon = MIcons.plus,
                            size = MButtonSize.Small,
                        )
                    },
                )
            }
            val shares = state?.shares
            when {
                shares == null && state?.error == null -> items(2, key = { "shares-skeleton-$it" }) { MSkeletonRow() }
                shares == null -> Unit
                shares.isEmpty() -> item(key = "shares-empty") {
                    MListRow(title = "Пока только ты", subtitle = "Друг зайдёт в панель со своими правами", icon = MIcons.users)
                }
                else -> items(shares, key = { "share:" + it.userId }) { share ->
                    MListRow(
                        title = shareName(share),
                        modifier = Modifier.animateItem(),
                        subtitle = rightsSummary(share.permissions),
                        icon = MIcons.user,
                        onClick = { shareEdit = ShareEdit.Existing(share) },
                    )
                }
            }
            item(key = "keys-head") {
                ToolsHead(
                    title = "Ключи API",
                    subtitle = "Для ботов и скриптов",
                    action = {
                        MSecondaryButton(
                            "Создать",
                            onClick = { keySheet = true },
                            icon = MIcons.key,
                            size = MButtonSize.Small,
                        )
                    },
                )
            }
            val keys = state?.keys
            when {
                keys == null && state?.error == null -> items(1, key = { "keys-skeleton-$it" }) { MSkeletonRow() }
                keys == null -> Unit
                keys.isEmpty() -> item(key = "keys-empty") {
                    MListRow(title = "Ключей нет", subtitle = "Ключ даёт боту доступ без пароля", icon = MIcons.key)
                }
                else -> items(keys, key = { "key:" + it.id }) { key ->
                    MListRow(
                        title = key.name.ifBlank { "Ключ" },
                        modifier = Modifier.animateItem(),
                        subtitle = keySubtitle(key),
                        icon = MIcons.key,
                        trailing = {
                            MIconButton(
                                MIcons.trash,
                                onClick = { revoking = key },
                                enabled = tools.key(serverId, "access:key:${key.id}") !in busy,
                                label = "Отозвать ключ ${key.name}",
                            )
                        },
                    )
                }
            }
        } else if (access != null) {
            item(key = "guest") {
                MListRow(
                    title = "Доступами управляет владелец",
                    subtitle = "У тебя: ${rightsSummary(access.permissions)}",
                    icon = MIcons.lock,
                )
            }
        }
        if (canFiles) {
            item(key = "sftp-head") { ToolsHead(title = "SFTP", subtitle = "Файлы сервера из любой программы") }
            val sftp = state?.sftp
            if (sftp == null && state?.error == null) {
                item(key = "sftp-skeleton") { MSkeleton(height = 120.dp) }
            } else if (sftp != null) {
                item(key = "sftp") {
                    SftpCard(
                        sftp = sftp,
                        full = full,
                        working = tools.key(serverId, "access:sftp") in busy,
                        onCopy = copy,
                        onIssue = { tools.issueSftp(serverId) },
                        onRevoke = { closingSftp = true },
                        onLocked = { locked = "SFTP" },
                    )
                }
            }
        }
    }

    shareEdit?.let { edit ->
        val existing = (edit as? ShareEdit.Existing)?.share
        ShareSheet(
            existing = existing,
            working = if (existing == null) {
                tools.key(serverId, "access:share") in busy
            } else {
                tools.key(serverId, "access:share:${existing.userId}") in busy
            },
            onDismiss = { shareEdit = null },
            onSubmit = { login, permissions ->
                if (existing == null) {
                    tools.addShare(serverId, login, permissions) { shareEdit = null }
                } else {
                    tools.updateShare(serverId, existing, permissions) { shareEdit = null }
                }
            },
            onRemove = {
                if (existing != null) {
                    shareEdit = null
                    removing = existing
                }
            },
        )
    }

    if (keySheet) {
        KeySheet(
            working = tools.key(serverId, "access:key") in busy,
            onDismiss = { keySheet = false },
            onCreate = { name, permissions -> tools.createApiKey(serverId, name, permissions) { keySheet = false } },
        )
    }

    removing?.let { share ->
        MConfirmDialog(
            title = "Забрать доступ?",
            text = "${shareName(share)} больше не откроет панель сервера",
            confirm = "Забрать",
            onConfirm = {
                removing = null
                tools.removeShare(serverId, share)
            },
            onDismiss = { removing = null },
        )
    }

    revoking?.let { key ->
        MConfirmDialog(
            title = "Отозвать ключ?",
            text = "Бот с ключом «${key.name}» сразу потеряет доступ",
            confirm = "Отозвать",
            onConfirm = {
                revoking = null
                tools.revokeApiKey(serverId, key)
            },
            onDismiss = { revoking = null },
        )
    }

    if (closingSftp) {
        MConfirmDialog(
            title = "Отозвать доступ по SFTP?",
            text = "Все подключённые программы отключатся",
            confirm = "Отозвать",
            onConfirm = {
                closingSftp = false
                tools.revokeSftp(serverId)
            },
            onDismiss = { closingSftp = false },
        )
    }

    val freshKey = state?.freshKey
    val sftpPassword = state?.sftpPassword
    when {
        freshKey != null -> ToolsSecretDialog(
            title = "Ключ создан",
            secret = freshKey,
            onCopy = { copySecret("Ключ", freshKey) },
            onDone = { tools.dismissSecret() },
        )
        sftpPassword != null -> ToolsSecretDialog(
            title = "Пароль SFTP",
            secret = sftpPassword,
            onCopy = { copySecret("Пароль", sftpPassword) },
            onDone = { tools.dismissSecret() },
        )
    }

    locked?.let { feature ->
        ToolsPlanDialog(feature, serverId, tools) { locked = null }
    }
}

private fun shareName(share: HostingShare): String = share.nickname.ifBlank { share.email.ifBlank { "Участник" } }

private fun rightsSummary(permissions: List<String>): String {
    val known = permissions.filter { it in ALL_PERMISSIONS }
    return when {
        known.isEmpty() -> "без прав"
        known.toSet() == ALL_PERMISSIONS -> "полный доступ"
        known.size == 1 -> HOSTING_PERMISSIONS.first { it.first == known.first() }.second
        else -> toolsRightsCount(known.size)
    }
}

private fun keySubtitle(key: HostingApiKey): String {
    val used = toolsMoment(key.lastUsedAt)
    val prefix = if (key.prefix.isNotBlank()) "${key.prefix}…" else ""
    val usage = if (used.isEmpty()) "ещё не использован" else "использован $used"
    return listOf(prefix, usage).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun SftpCard(
    sftp: HostingSftp,
    full: Boolean,
    working: Boolean,
    onCopy: (String, String) -> Unit,
    onIssue: () -> Unit,
    onRevoke: () -> Unit,
    onLocked: () -> Unit,
) {
    val colors = millida
    val active = sftp.active
    MCard {
        if (active) {
            val until = toolsMoment(sftp.expiresAt)
            CredentialRow("Хост", sftp.host, onCopy)
            CredentialRow("Порт", sftp.port.takeIf { it > 0 }?.toString().orEmpty(), onCopy)
            CredentialRow("Логин", sftp.login, onCopy)
            if (until.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Действует до $until", style = MillidaType.small, color = colors.fgSubtle)
            }
        } else {
            Text("Доступ не выдан", style = MillidaType.rowTitle, color = colors.fg)
            Spacer(Modifier.height(2.dp))
            Text("Пароль покажем один раз", style = MillidaType.small, color = colors.fgSubtle)
        }
        Spacer(Modifier.height(12.dp))
        MButtonRow {
            if (!full && !active) {
                ToolsLockButton("Выдать доступ", onLocked)
            } else {
                MSecondaryButton(
                    if (active) "Новый пароль" else "Выдать доступ",
                    onClick = onIssue,
                    icon = MIcons.key,
                    size = MButtonSize.Small,
                    loading = working,
                )
            }
            if (active) {
                MTextButton("Отозвать", onClick = onRevoke, enabled = !working, color = colors.danger)
            }
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, onCopy: (String, String) -> Unit) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            style = MillidaType.small,
            color = colors.fgSubtle,
            maxLines = 1,
        )
        Text(
            value.ifBlank { "—" },
            modifier = Modifier.weight(1f),
            style = MillidaType.pixel,
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value.isNotBlank()) {
            MIconButton(MIcons.copy, onClick = { onCopy(label, value) }, label = "Скопировать: $label")
        }
    }
}

@Composable
private fun RolePicker(selected: Set<String>, onPick: (Set<String>) -> Unit) {
    MButtonRow {
        ROLE_PRESETS.forEach { (label, set) ->
            MChip(text = label, selected = selected == set, onClick = { onPick(set) })
        }
    }
}

@Composable
private fun ShareSheet(
    existing: HostingShare?,
    working: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, Set<String>) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = millida
    var login by remember(existing) { mutableStateOf("") }
    var permissions by remember(existing) {
        mutableStateOf(existing?.permissions?.filter { it in ALL_PERMISSIONS }?.toSet() ?: DEFAULT_SHARE)
    }
    MBottomSheet(onDismiss = onDismiss, title = existing?.let { shareName(it) } ?: "Дать доступ") {
        if (existing == null) {
            MField(
                value = login,
                onValueChange = { login = it },
                placeholder = "Ник или почта в Millida",
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(16.dp))
        }
        MSectionTitle("РОЛЬ")
        Spacer(Modifier.height(8.dp))
        RolePicker(permissions) { permissions = it }
        Spacer(Modifier.height(16.dp))
        MSectionTitle("ПРАВА")
        Spacer(Modifier.height(8.dp))
        ToolsPermissionChips(permissions) { id -> permissions = if (id in permissions) permissions - id else permissions + id }
        Spacer(Modifier.height(18.dp))
        MPrimaryButton(
            if (existing == null) "Дать доступ" else "Сохранить",
            onClick = { onSubmit(login, permissions + existing?.permissions.orEmpty().filterNot { it in ALL_PERMISSIONS }) },
            modifier = Modifier.fillMaxWidth(),
            enabled = permissions.isNotEmpty() && (existing != null || login.isNotBlank()),
            loading = working,
        )
        if (existing != null) {
            Spacer(Modifier.height(8.dp))
            MTextButton(
                "Забрать доступ",
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                color = colors.danger,
            )
        }
    }
}

@Composable
private fun KeySheet(working: Boolean, onDismiss: () -> Unit, onCreate: (String, Set<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var permissions by remember { mutableStateOf(DEFAULT_SHARE) }
    MBottomSheet(onDismiss = onDismiss, title = "Новый ключ API") {
        MField(
            value = name,
            onValueChange = { name = it.take(KEY_NAME_MAX) },
            placeholder = "Название, например «бот Discord»",
            imeAction = ImeAction.Done,
        )
        Spacer(Modifier.height(16.dp))
        MSectionTitle("ЧТО РАЗРЕШЕНО")
        Spacer(Modifier.height(8.dp))
        ToolsPermissionChips(permissions) { id -> permissions = if (id in permissions) permissions - id else permissions + id }
        Spacer(Modifier.height(18.dp))
        MPrimaryButton(
            "Создать ключ",
            onClick = { onCreate(name, permissions) },
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank() && permissions.isNotEmpty(),
            icon = MIcons.key,
            loading = working,
        )
    }
}

private const val KEY_NAME_MAX = 60
