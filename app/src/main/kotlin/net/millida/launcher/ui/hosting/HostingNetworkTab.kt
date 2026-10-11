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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.HostingToolsModel
import net.millida.launcher.core.hosting.HostingPort
import net.millida.launcher.core.hosting.HostingToolAccess
import net.millida.launcher.forServer
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
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

@Composable
fun HostingNetworkTab(serverId: String, tools: HostingToolsModel) {
    LaunchedEffect(serverId) { tools.openNetwork(serverId) }
    val all by tools.network.collectAsState()
    val busy by tools.busy.collectAsState()
    val rights by tools.rights.collectAsState()
    val access = rights.forServer(serverId)
    val state = all.takeIf { it.serverId == serverId }
    val copy = rememberToolsCopy(tools)

    var slugAsk by remember { mutableStateOf<String?>(null) }
    var unbind by remember { mutableStateOf(false) }
    var portSheet by remember { mutableStateOf(false) }
    var freeing by remember { mutableStateOf<HostingPort?>(null) }
    var locked by remember { mutableStateOf<String?>(null) }

    if (access != null && !access.can("network")) {
        ToolsList { item(key = "no-rights") { ToolsNoRights() } }
    } else {
        val network = state?.network
        val host = network?.address?.ifBlank { null } ?: access?.address?.substringBefore(':').orEmpty()
        val limit = network?.limit ?: 0
        val portsOpen = (access?.fullAccess ?: true) && limit > 0
        ToolsList {
            if (access == null) {
                items(3, key = { "skeleton-$it" }) { MSkeleton(height = 120.dp) }
            } else {
                item(key = "address") { AddressCard(access.address, onCopy = { copy("Адрес", access.address) }) }
                item(key = "slug") {
                    SlugCard(
                        access = access,
                        working = tools.key(serverId, "network:slug") in busy,
                        onApply = { slugAsk = it },
                    )
                }
                item(key = "domain") {
                    DomainCard(
                        current = access.customDomain,
                        hint = state?.dnsHint.orEmpty(),
                        working = tools.key(serverId, "network:domain") in busy,
                        onBind = { tools.setDomain(serverId, it) },
                        onUnbind = { unbind = true },
                    )
                }
            }
            item(key = "ports-head") {
                ToolsHead(
                    title = "Дополнительные порты",
                    subtitle = network?.let { "Занято ${it.ports.size} из ${it.limit}" },
                    action = {
                        if (portsOpen) {
                            MSecondaryButton(
                                "Выдать",
                                onClick = { portSheet = true },
                                enabled = network != null && network.ports.size < limit,
                                icon = MIcons.plus,
                                size = MButtonSize.Small,
                            )
                        } else if (network != null || state?.error != null) {
                            ToolsLockButton("Выдать") { locked = "Дополнительные порты" }
                        }
                    },
                )
            }
            val error = state?.error
            when {
                error != null && network == null -> item(key = "ports-error") {
                    MErrorCard(error, onRetry = { tools.loadNetwork(serverId) })
                }
                network == null -> items(2, key = { "ports-skeleton-$it" }) { MSkeletonRow() }
                network.ports.isEmpty() -> item(key = "ports-empty") {
                    MListRow(
                        title = "Портов нет",
                        subtitle = if (portsOpen) "Нужны голосовому чату, карте и ботам" else "На этом тарифе портов нет",
                        icon = MIcons.link,
                    )
                }
                else -> {
                    if (error != null) {
                        item(key = "ports-error") { MErrorCard(error, onRetry = { tools.loadNetwork(serverId) }) }
                    }
                    items(network.ports, key = { "port:" + it.port }) { port ->
                        val line = "$host:${port.port}"
                        MListRow(
                            title = line,
                            modifier = Modifier.animateItem(),
                            subtitle = port.note.ifBlank { null },
                            icon = MIcons.link,
                            trailing = {
                                MIconButton(MIcons.copy, onClick = { copy("Адрес", line) }, label = "Скопировать $line")
                                MIconButton(
                                    MIcons.trash,
                                    onClick = { freeing = port },
                                    enabled = tools.key(serverId, "network:port:${port.port}") !in busy,
                                    label = "Освободить порт ${port.port}",
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    slugAsk?.let { slug ->
        MConfirmDialog(
            title = "Сменить адрес?",
            text = "Старый адрес перестанет работать у всех, кто его сохранил",
            confirm = "Сменить",
            onConfirm = {
                slugAsk = null
                tools.changeAddress(serverId, slug)
            },
            onDismiss = { slugAsk = null },
        )
    }

    if (unbind) {
        MConfirmDialog(
            title = "Отвязать домен?",
            text = "Игроки больше не зайдут по ${access?.customDomain.orEmpty()}",
            confirm = "Отвязать",
            onConfirm = {
                unbind = false
                tools.setDomain(serverId, "")
            },
            onDismiss = { unbind = false },
        )
    }

    freeing?.let { port ->
        MConfirmDialog(
            title = "Освободить порт ${port.port}?",
            text = "Плагин или мод на этом порту перестанет быть доступен",
            confirm = "Освободить",
            onConfirm = {
                freeing = null
                tools.removePort(serverId, port.port)
            },
            onDismiss = { freeing = null },
        )
    }

    if (portSheet) {
        var note by remember { mutableStateOf("") }
        val working = tools.key(serverId, "network:port") in busy
        MBottomSheet(onDismiss = { portSheet = false }, title = "Выдать порт") {
            MField(
                value = note,
                onValueChange = { note = it.take(PORT_NOTE_MAX) },
                placeholder = "Подпись, например «голосовой чат»",
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(14.dp))
            MPrimaryButton(
                "Выдать порт",
                onClick = { tools.addPort(serverId, note) { portSheet = false } },
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.plus,
                loading = working,
            )
        }
    }

    locked?.let { feature ->
        ToolsPlanDialog(feature, serverId, tools) { locked = null }
    }
}

private const val PORT_NOTE_MAX = 60

@Composable
private fun AddressCard(address: String, onCopy: () -> Unit) {
    val colors = millida
    MCard {
        MSectionTitle("КАК ЗАХОДЯТ ИГРОКИ")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                address.ifBlank { "Адрес появится после запуска" },
                modifier = Modifier.weight(1f),
                style = if (address.isBlank()) MillidaType.body else MillidaType.pixel,
                color = if (address.isBlank()) colors.fgSubtle else colors.accentText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (address.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                MIconButton(MIcons.copy, onClick = onCopy, label = "Скопировать адрес")
            }
        }
    }
}

@Composable
private fun SlugCard(access: HostingToolAccess, working: Boolean, onApply: (String) -> Unit) {
    val colors = millida
    var draft by remember(access.slug) { mutableStateOf(access.slug) }
    val suffix = slugSuffix(access.slug, access.address)
    val clean = draft.trim()
    val ready = clean.length >= SLUG_MIN && !clean.startsWith("-") && !clean.endsWith("-") && clean != access.slug && !working
    MCard {
        MSectionTitle("КОРОТКИЙ АДРЕС")
        Spacer(Modifier.height(8.dp))
        MField(
            value = draft,
            onValueChange = { typed -> draft = typed.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(SLUG_MAX) },
            placeholder = "my-server",
            keyboardType = KeyboardType.Uri,
            onImeAction = { if (ready) onApply(clean) },
        )
        if (suffix.isNotEmpty() && clean.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("$clean$suffix", style = MillidaType.small, color = colors.fgFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        MSecondaryButton(
            "Сменить",
            onClick = { onApply(clean) },
            enabled = ready,
            size = MButtonSize.Small,
            loading = working,
        )
    }
}

@Composable
private fun DomainCard(
    current: String,
    hint: String,
    working: Boolean,
    onBind: (String) -> Unit,
    onUnbind: () -> Unit,
) {
    val colors = millida
    var draft by remember(current) { mutableStateOf(current) }
    val clean = draft.trim().lowercase()
    val ready = clean.contains('.') && clean != current && !working
    MCard {
        MSectionTitle("СВОЙ ДОМЕН")
        Spacer(Modifier.height(8.dp))
        MField(
            value = draft,
            onValueChange = { draft = it.replace(" ", "") },
            placeholder = "play.example.ru",
            keyboardType = KeyboardType.Uri,
            onImeAction = { if (ready) onBind(clean) },
        )
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(hint.replaceFirstChar { it.uppercase() }, style = MillidaType.small, color = colors.fgSubtle)
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MSecondaryButton(
                "Привязать",
                onClick = { onBind(clean) },
                enabled = ready,
                size = MButtonSize.Small,
                loading = working,
            )
            if (current.isNotBlank()) {
                MTextButton("Отвязать", onClick = onUnbind, enabled = !working, color = colors.danger)
            }
        }
    }
}

private const val SLUG_MIN = 3
private const val SLUG_MAX = 40

private fun slugSuffix(slug: String, address: String): String {
    val hostOnly = address.substringBefore(':')
    if (slug.isEmpty() || !hostOnly.startsWith("$slug.")) return ""
    return hostOnly.removePrefix(slug)
}
