package net.millida.launcher.ui.build

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.servers.ServerEntry
import net.millida.launcher.core.servers.ServerStatus
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.MotdText
import net.millida.launcher.ui.home.itemMotion
import net.millida.launcher.ui.millida

internal fun LazyListScope.serversTab(
    profile: Profile,
    state: UiState,
    model: LauncherViewModel,
    onAdd: () -> Unit,
) {
    val servers = state.buildServers.distinctBy { it.address }
    val error = state.errorOf(Area.BUILD)
    if (servers.isEmpty() && state.isLoading(Area.BUILD)) {
        items(2, key = { "servers:skeleton:$it" }, contentType = { "skeleton" }) { MSkeletonRow() }
        return
    }
    if (servers.isEmpty() && error != null) {
        item(key = "servers:error", contentType = "error") {
            MErrorCard(error, onRetry = { model.retry(Area.BUILD) })
        }
        return
    }
    if (servers.isEmpty()) {
        item(key = "servers:empty", contentType = "empty") {
            MEmptyState(
                icon = MIcons.server,
                title = "Серверов нет",
                actionText = "Добавить",
                onAction = onAdd,
                actionIcon = MIcons.plus,
            )
        }
        return
    }
    item(key = "servers:actions", contentType = "actions") {
        MButtonRow {
            MSecondaryButton("Добавить сервер", onClick = onAdd, icon = MIcons.plus, size = MButtonSize.Small)
        }
    }
    items(servers, key = { "server:${it.address}" }, contentType = { "server" }) { entry ->
        ServerRow(
            profile,
            entry,
            state.serverStatuses[entry.address],
            entry.address in state.silentServers,
            state.busy != null,
            model,
            itemMotion(),
        )
    }
}

@Composable
private fun ServerRow(
    profile: Profile,
    entry: ServerEntry,
    status: ServerStatus?,
    silent: Boolean,
    busy: Boolean,
    model: LauncherViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    LaunchedEffect(entry.address) { model.pingServer(entry.address) }
    var confirming by rememberSaveable(entry.address) { mutableStateOf(false) }

    val head: @Composable () -> Unit = {
        ServerIcon(status?.iconBase64 ?: entry.icon)
        Spacer(Modifier.width(12.dp))
    }
    val titles: @Composable () -> Unit = {
        Text(
            entry.name.ifEmpty { entry.address },
            style = MillidaType.rowTitle,
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.address,
            style = MillidaType.pixelSmall,
            color = colors.fgFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val actions: @Composable () -> Unit = {
        MSecondaryButton(
            "Играть",
            onClick = {
                model.selectBuild(profile)
                model.playOnAddress(entry.address, entry.name)
            },
            enabled = !busy,
            icon = MIcons.play,
            size = MButtonSize.Small,
        )
        MSecondaryButton(
            "Закрепить",
            onClick = { model.pinBuildServer(profile, entry.address) },
            enabled = !busy,
            icon = MIcons.star,
            size = MButtonSize.Small,
        )
        MIconButton(
            icon = MIcons.trash,
            onClick = { confirming = true },
            enabled = !busy,
            label = "Убрать сервер",
        )
    }

    MCard(modifier = modifier, contentPadding = 14.dp) {
        if (LocalWideLayout.current) {
            WideServerLine(head, titles, actions) { ServerStatusLines(status, silent) }
            return@MCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            head()
            Column(Modifier.weight(1f)) { titles() }
        }
        Spacer(Modifier.height(8.dp))
        ServerStatusLines(status, silent)
        Spacer(Modifier.height(12.dp))
        MButtonRow { actions() }
    }

    if (confirming) {
        MConfirmDialog(
            title = "Убрать сервер?",
            text = "Он пропадёт из списка этой сборки.",
            confirm = "Убрать",
            onConfirm = {
                confirming = false
                model.removeBuildServer(profile, entry.address)
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun ServerStatusLines(status: ServerStatus?, silent: Boolean) {
    val colors = millida
    if (status == null) {
        Text(if (silent) "Сервер не отвечает" else "Опрашиваем сервер", style = MillidaType.small, color = colors.fgFaint)
        return
    }
    Text(
        buildString {
            append("Онлайн ").append(status.online).append(" из ").append(status.max)
            if (status.version.isNotEmpty()) append(" · ").append(status.version)
            append(" · ").append(status.latencyMs).append(" мс")
        },
        style = MillidaType.small,
        color = colors.fgSubtle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (status.motd.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        MotdText(status.motd)
    }
}

private val SERVER_LINE_MIN_WIDTH = 560.dp

/**
 * The desktop's `.srv-line`: logo, name with its state, and the buttons at
 * the end of the same line. A card too narrow for that keeps the stacked look.
 */
@Composable
private fun WideServerLine(
    head: @Composable () -> Unit,
    titles: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    status: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= SERVER_LINE_MIN_WIDTH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                head()
                Column(Modifier.weight(1f)) {
                    titles()
                    Spacer(Modifier.height(4.dp))
                    status()
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    head()
                    Column(Modifier.weight(1f)) { titles() }
                }
                Spacer(Modifier.height(8.dp))
                status()
                Spacer(Modifier.height(12.dp))
                MButtonRow { actions() }
            }
        }
    }
}

/** The logo comes inside the ping answer, as base64, and never as a link. */
@Composable
private fun ServerIcon(data: String?) {
    val bitmap by produceState<ImageBitmap?>(null, data) {
        value = withContext(Dispatchers.Default) { decodeServerIcon(data) }
    }
    val decoded = bitmap
    if (decoded == null) {
        MRemoteImage(null, MIcons.server, size = 44.dp)
        return
    }
    Image(
        bitmap = decoded,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(44.dp)
            .clip(MillidaShape.control),
    )
}

private fun decodeServerIcon(data: String?): ImageBitmap? {
    val raw = data?.substringAfterLast(',')?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return runCatching {
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ICON_SIDE || bounds.outHeight !in 1..MAX_ICON_SIDE) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

private const val MAX_ICON_SIDE = 128

@Composable
internal fun AddServerSheet(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    MBottomSheet(onDismiss = onDismiss, title = "Добавить сервер") {
        MField(name, { name = it }, placeholder = "Название")
        Spacer(Modifier.height(8.dp))
        MField(
            value = address,
            onValueChange = { address = it },
            placeholder = "Адрес, например mc.millida.net",
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(18.dp))
        MPrimaryButton(
            "Добавить",
            onClick = { onAdd(name.trim(), address.trim()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = address.isNotBlank(),
            icon = MIcons.plus,
            size = MButtonSize.Large,
        )
    }
}
