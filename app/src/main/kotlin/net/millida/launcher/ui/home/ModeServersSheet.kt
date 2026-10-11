package net.millida.launcher.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.hub.Hub
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.versionRangeLabel
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.millida

/** A mode's servers, the busiest first; «Играть» joins the one chosen. */
@Composable
internal fun ModeServersSheet(
    sheet: ModeSheet,
    joining: String,
    onPlay: (RatedServer) -> Unit,
    onOpen: (RatedServer) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss, title = sheet.mode.title) {
        val servers = sheet.servers
        when {
            sheet.error != null && servers == null -> MErrorCard(sheet.error, onRetry = onRetry)
            servers == null -> repeat(4) {
                MSkeletonRow()
                Spacer(Modifier.height(8.dp))
            }
            servers.isEmpty() -> MErrorCard("Серверов этого режима сейчас нет", onRetry = onRetry)
            else -> servers.forEach { server ->
                MListRow(
                    title = server.name.ifBlank { server.joinAddress },
                    subtitle = listOfNotNull(
                        Hub.onlineLabel(server.online.takeIf { server.up }),
                        versionRangeLabel(server.versionMajors),
                    ).joinToString(" · ").ifEmpty { null },
                    onClick = { onOpen(server) },
                    plate = false,
                    leading = {
                        MRemoteImage(
                            url = server.logoUrl,
                            icon = MIcons.server,
                            size = 40.dp,
                            tint = if (server.up) colors.accent else colors.fgFaint,
                            background = if (server.up) colors.accentSoft else colors.surface2,
                        )
                    },
                    trailing = {
                        MButton(
                            text = "Играть",
                            onClick = { onPlay(server) },
                            variant = MButtonVariant.Secondary,
                            size = MButtonSize.Small,
                            icon = MIcons.play,
                            loading = joining.isNotEmpty() && joining == server.joinAddress,
                            enabled = joining.isEmpty() || joining == server.joinAddress,
                        )
                    },
                )
            }
        }
    }
}
