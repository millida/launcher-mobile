package net.millida.launcher.ui.servers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.versionRangeLabel
import net.millida.launcher.core.servers.OwnServers
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MInfoRow
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.MotdPlate
import net.millida.launcher.ui.WIDE_ROOMY_HEIGHT_DP
import net.millida.launcher.ui.catalog.plainText
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida

private const val COLLAPSED_LINES = 5
private const val SUMMARY_LINES = 3
private val BANNER_HEIGHT = 96.dp
private val WIDE_BANNER_HEIGHT = 150.dp
private val MOTD_INSET = 12.dp

@Composable
fun ServerDetailSheet(
    server: RatedServer,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onCopy: () -> Unit,
    onOpenLink: (String) -> Unit,
    joining: Boolean = false,
) {
    val colors = millida
    val address = server.joinAddress
    val launcherOnly = OwnServers.of(server.slug, address) != null
    val about = remember(server.description) { plainText(server.description) }
    val extra = about.takeIf { it.isNotEmpty() && it.replace('\n', ' ') != server.summary }
    var expanded by remember(server.slug) { mutableStateOf(false) }
    val links = listOfNotNull(
        webLink(server.websiteUrl)?.let { "Сайт" to it },
        webLink(server.discordInviteUrl)?.let { "Discord" to it },
    )

    val wide = LocalWideLayout.current
    val bannerHeight = if (wide && LocalConfiguration.current.screenHeightDp >= WIDE_ROOMY_HEIGHT_DP) {
        WIDE_BANNER_HEIGHT
    } else {
        BANNER_HEIGHT
    }

    MBottomSheet(onDismiss = onDismiss) {
        ServerBanner(server, bannerHeight)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MRemoteImage(
                url = server.logoUrl,
                icon = MIcons.server,
                size = 60.dp,
                pixelated = true,
                tint = if (server.up) colors.accentText else colors.fgFaint,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MillidaType.dialogTitle,
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (server.up) {
                        MBadge("${onlineText(server)} онлайн", colors.accentText, colors.accentSoft, icon = MIcons.users)
                    } else {
                        MBadge("офлайн", colors.fgSubtle, colors.surface2)
                    }
                    ServerTags(server, withVersions = false)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (wide) {
            WideActions(server, address.isNotEmpty() || launcherOnly, links, onPlay, onOpenLink, joining)
        } else if (address.isNotEmpty() || launcherOnly) {
            MPrimaryButton(
                text = if (server.up) "Играть" else "Сервер не отвечает",
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth(),
                enabled = server.up,
                icon = MIcons.play,
                size = MButtonSize.Large,
                loading = joining,
            )
        } else {
            MBadge("Вход по заявке — смотри сайт сервера", colors.fgMuted, colors.surface2, icon = MIcons.lock)
        }
        if (server.summary.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                server.summary,
                style = MillidaType.body,
                color = colors.fgMuted,
                maxLines = SUMMARY_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (extra != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                extra,
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            MTextButton(
                text = if (expanded) "Свернуть" else "Читать дальше",
                onClick = { expanded = !expanded },
                color = colors.accentText,
            )
        }
        Spacer(Modifier.height(14.dp))
        MCard(contentPadding = 6.dp) {
            if (address.isNotEmpty()) {
                MListRow(
                    title = address,
                    subtitle = "Скопировать адрес",
                    icon = MIcons.link,
                    onClick = onCopy,
                    plate = false,
                    trailing = { MIcon(MIcons.copy, size = 18.dp, color = colors.fgSubtle) },
                )
                MDivider()
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                versionRangeLabel(server.versionMajors)?.let { MInfoRow("Версии", it, mono = true) }
                MInfoRow(
                    label = "Сейчас играют",
                    value = if (server.up) onlineText(server) else "—",
                    valueColor = if (server.up) colors.accentText else colors.fgFaint,
                    mono = true,
                )
                if (server.avgOnline > 0) {
                    MInfoRow("В среднем за сутки", groupDigits(server.avgOnline.toLong()), mono = true)
                }
                if (server.votes30d > 0) {
                    MInfoRow("Голоса за месяц", groupDigits(server.votes30d.toLong()), mono = true)
                }
                server.bedrockAddress?.takeIf { it.isNotBlank() }?.let { MInfoRow("Bedrock", it, mono = true) }
            }
        }
        if (server.screenshots.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            MSectionTitle("СКРИНШОТЫ")
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(server.screenshots.filter { it.isNotBlank() }.take(12)) { shot ->
                    MRemoteImage(
                        url = shot,
                        icon = MIcons.image,
                        modifier = Modifier.width(220.dp).height(124.dp),
                        shape = MillidaShape.control,
                    )
                }
            }
        }
        if (!wide && links.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MButtonRow {
                links.forEach { (label, url) ->
                    MSecondaryButton(
                        text = label,
                        onClick = { onOpenLink(url) },
                        icon = MIcons.externalLink,
                        size = MButtonSize.Small,
                    )
                }
            }
        }
    }
}

/**
 * The desktop card's action row: «Играть» takes the room and the server's own
 * pages sit beside it, so a wide window does not spend a second row on them.
 */
@Composable
private fun WideActions(
    server: RatedServer,
    joinable: Boolean,
    links: List<Pair<String, String>>,
    onPlay: () -> Unit,
    onOpenLink: (String) -> Unit,
    joining: Boolean,
) {
    val colors = millida
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (joinable) {
            MPrimaryButton(
                text = if (server.up) "Играть" else "Сервер не отвечает",
                onClick = onPlay,
                modifier = Modifier.weight(1f),
                enabled = server.up,
                icon = MIcons.play,
                loading = joining,
            )
        } else {
            Box(Modifier.weight(1f)) {
                MBadge("Вход по заявке — смотри сайт сервера", colors.fgMuted, colors.surface2, icon = MIcons.lock)
            }
        }
        links.forEach { (label, url) ->
            MSecondaryButton(
                text = label,
                onClick = { onOpenLink(url) },
                icon = MIcons.externalLink,
            )
        }
    }
}

@Composable
private fun ServerBanner(server: RatedServer, height: Dp) {
    val banner = server.bannerUrl?.trim().orEmpty()
    when {
        banner.isNotEmpty() -> MRemoteImage(
            url = banner,
            icon = MIcons.image,
            modifier = Modifier.fillMaxWidth().height(height),
            shape = MillidaShape.card,
        )
        server.motd.isNotBlank() -> MotdPlate(server.motd, maxLines = 3, large = true, minHeight = height - MOTD_INSET)
    }
}
