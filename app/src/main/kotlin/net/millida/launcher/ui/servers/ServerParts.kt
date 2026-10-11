package net.millida.launcher.ui.servers

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.bedrock.isBedrockAddress
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.versionRangeLabel
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.categoryLabel
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida

/** Pages of the rating overlap when the order shifts between them, so rows are keyed and deduplicated by this. */
internal fun serverKey(server: RatedServer): String = "srv:" + server.slug.ifEmpty { server.ip }.ifEmpty { server.name }

internal fun onlineText(server: RatedServer): String =
    (if (server.onlineApprox) "~" else "") + groupDigits(server.online.toLong())

/** Only web pages leave the launcher: an owner-written link must not start another app by its scheme. */
internal fun webLink(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    return url.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}

@Composable
internal fun OnlineCount(server: RatedServer, modifier: Modifier = Modifier) {
    val colors = millida
    if (server.up) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.users, size = 14.dp, color = colors.accentText)
            Spacer(Modifier.width(5.dp))
            Text(onlineText(server), style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
        }
    } else {
        Text("офлайн", modifier = modifier, style = MillidaType.small, color = colors.fgFaint, maxLines = 1)
    }
}

@Composable
internal fun ServerTags(server: RatedServer, withVersions: Boolean = true) {
    val colors = millida
    if (withVersions) {
        versionRangeLabel(server.versionMajors)?.let { range ->
            MBadge(range, colors.fgMuted, colors.surface2, icon = MIcons.blocks)
        }
    }
    server.categories.take(1).forEach { category ->
        MBadge(categoryLabel(category), colors.info, colors.infoSoft)
    }
    if (server.license.equals("CRACKED", ignoreCase = true)) {
        MBadge("Без лицензии", colors.fgMuted, colors.surface2)
    }
    if (isBedrockAddress(server.joinAddress) || !server.bedrockAddress.isNullOrBlank()) {
        MBadge("Bedrock", colors.warning, colors.warningSoft)
    }
}
