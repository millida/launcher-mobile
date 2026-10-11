package net.millida.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.JoinPlan
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.versionRangeLabel
import net.millida.launcher.core.servers.OwnServers
import net.millida.launcher.joinableBuilds
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionHeader
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida

private val TILE_WIDTH = 252.dp
private const val SHELF_LIMIT = 10

internal fun LazyListScope.serverShelf(
    lobby: LobbyState,
    onPlay: (RatedServer) -> Unit,
    onOpen: (RatedServer) -> Unit,
    onAll: () -> Unit,
    onRetry: () -> Unit,
) {
    val joining = lobby.joining
    val servers = lobby.topServers.distinctBy { it.slug.ifEmpty { it.joinAddress } }.take(SHELF_LIMIT)
    val loading = lobby.serversLoading && servers.isEmpty()
    val error = lobby.serversError?.takeIf { servers.isEmpty() }
    if (servers.isEmpty() && !loading && error == null) return
    item(key = "shelf:servers", contentType = "shelf") {
        Column(Modifier.fillMaxWidth()) {
            MSectionHeader("Куда пойти играть", action = "Все", onAction = onAll)
            Spacer(Modifier.height(4.dp))
            when {
                servers.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(servers, key = { it.slug.ifEmpty { it.joinAddress } }, contentType = { "server" }) { server ->
                        ServerTile(
                            server = server,
                            joining = joining,
                            onPlay = { onPlay(server) },
                            onOpen = { onOpen(server) },
                            modifier = itemMotion(),
                        )
                    }
                }
                error != null -> MErrorCard(error, onRetry = onRetry)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MSkeleton(height = 132.dp, modifier = Modifier.width(TILE_WIDTH))
                    MSkeleton(height = 132.dp, modifier = Modifier.width(TILE_WIDTH))
                }
            }
        }
    }
}

@Composable
private fun ServerTile(
    server: RatedServer,
    joining: String,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(modifier = modifier.width(TILE_WIDTH), onClick = onOpen, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MRemoteImage(
                url = server.logoUrl,
                icon = MIcons.server,
                size = 44.dp,
                tint = if (server.up) colors.accent else colors.fgFaint,
                background = if (server.up) colors.accentSoft else colors.surface2,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.name.ifBlank { server.joinAddress },
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (server.up) colors.accent else colors.fgFaint),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (server.up) "${groupDigits(server.online.toLong())} онлайн" else "Не в сети",
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                versionRangeLabel(server.versionMajors) ?: "Любая версия",
                modifier = Modifier.weight(1f),
                style = MillidaType.pixelSmall,
                color = colors.fgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            if (server.joinAddress.isBlank() && OwnServers.of(server.slug, null) == null) {
                MButton(
                    text = "Открыть",
                    onClick = onOpen,
                    variant = MButtonVariant.Secondary,
                    size = MButtonSize.Small,
                    icon = MIcons.chevronRight,
                )
            } else {
                MButton(
                    text = "Играть",
                    onClick = onPlay,
                    variant = MButtonVariant.Secondary,
                    size = MButtonSize.Small,
                    icon = MIcons.play,
                    loading = joining.isNotEmpty() && joining == server.joinAddress,
                    enabled = joining.isEmpty() || joining == server.joinAddress,
                )
            }
        }
    }
}

@Composable
fun JoinServerSheet(state: UiState, model: LauncherViewModel, onCreateBuild: (String?) -> Unit) {
    val lobby by model.lobby.state.collectAsStateWithLifecycle()
    val prompt = lobby.join ?: return
    val colors = millida
    val wanted = prompt.wanted.joinToString(", ")
    val server = prompt.serverName.ifBlank { prompt.address }
    when (val plan = prompt.plan) {
        is JoinPlan.Create, is JoinPlan.Mismatch -> {
            val current = (plan as? JoinPlan.Mismatch)?.build
            MBottomSheet(
                onDismiss = model.lobby::dismissJoin,
                title = when {
                    current != null -> "Версия не совпадает"
                    prompt.version.isNotEmpty() -> "Нужна сборка ${prompt.version}"
                    else -> "Сначала создай сборку"
                },
            ) {
                Text(
                    when {
                        current != null -> "«$server» на $wanted, а «${current.name}» — ${current.gameVersion}"
                        wanted.isNotEmpty() -> "«$server» работает на $wanted"
                        else -> "«$server» не сказал свою версию"
                    },
                    style = MillidaType.body,
                    color = colors.fgMuted,
                )
                Spacer(Modifier.height(18.dp))
                if (prompt.version.isNotEmpty()) {
                    MPrimaryButton(
                        text = if (current != null) "Создать ${prompt.version}" else "Создать и играть",
                        onClick = { model.createBuildAndJoin(prompt.version, prompt.address, prompt.serverName) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = MIcons.play,
                        size = MButtonSize.Large,
                    )
                } else {
                    MPrimaryButton(
                        text = "Создать сборку",
                        onClick = {
                            model.lobby.dismissJoin()
                            onCreateBuild(prompt.wanted.firstOrNull())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = MIcons.plus,
                        size = MButtonSize.Large,
                    )
                }
                if (current != null) {
                    Spacer(Modifier.height(10.dp))
                    MSecondaryButton(
                        text = "Всё равно зайти",
                        onClick = { model.joinServer(current, prompt.address, prompt.serverName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is JoinPlan.Unknown -> {
            val builds = joinableBuilds(state.profiles).sortedByDescending { it.id == plan.build.id }
            MBottomSheet(onDismiss = model.lobby::dismissJoin, title = "Какой сборкой заходим?") {
                Text(
                    "«$server» не сказал свою версию",
                    style = MillidaType.body,
                    color = colors.fgMuted,
                )
                Spacer(Modifier.height(12.dp))
                builds.forEach { build ->
                    MListRow(
                        title = build.name,
                        subtitle = loaderLine(build),
                        onClick = { model.joinServer(build, prompt.address, prompt.serverName) },
                        plate = false,
                        leading = {
                            BuildArt(
                                profile = build,
                                meta = lobby.metas[build.id],
                                coverPath = lobby.covers[build.id],
                                coverStamp = lobby.coverStamps[build.id] ?: 0L,
                                modifier = Modifier.size(40.dp),
                                iconSize = 24.dp,
                            )
                        },
                    )
                }
            }
        }
        is JoinPlan.Launch, is JoinPlan.Switch -> Unit
    }
}
