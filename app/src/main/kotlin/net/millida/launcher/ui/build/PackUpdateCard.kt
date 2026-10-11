package net.millida.launcher.ui.build

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.packs.PackAutoUpdate
import net.millida.launcher.core.packs.PackUpdateOffer
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.catalog.ModpackJob
import net.millida.launcher.ui.millida

/** The newer published version of the pack, to take before «Играть» takes it anyway. */
@Composable
internal fun PackUpdateCard(offer: PackUpdateOffer, job: ModpackJob?, busy: Boolean, onUpdate: () -> Unit) {
    val colors = millida
    MCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.download, size = 20.dp, color = colors.accentText)
            Spacer(Modifier.width(10.dp))
            Text(
                "Новая версия · ${offer.from} → ${offer.to}",
                style = MillidaType.body,
                color = colors.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (job != null) {
                MButton(
                    text = job.progress?.let { "${(it * 100).toInt()}%" } ?: "Обновляем",
                    onClick = {},
                    size = MButtonSize.Small,
                    loading = true,
                )
            } else {
                MPrimaryButton(text = "Обновить", onClick = onUpdate, enabled = !busy, size = MButtonSize.Small)
            }
        }
        if (job != null) {
            Spacer(Modifier.height(10.dp))
            MProgressBar(progress = job.progress)
        }
    }
}

@Composable
internal fun PackUpdateConfirm(name: String, offer: PackUpdateOffer, edited: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    MConfirmDialog(
        title = "Обновить «$name»?",
        text = "Поставим версию ${offer.to} рядом со старой и перенесём миры, скриншоты и настройки игры. " +
            "Старая версия удалится, только когда новая встанет на место." +
            if (edited) " Моды придут в версии автора: добавленные тобой моды не перенесутся." else "",
        confirm = "Обновить",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        danger = false,
    )
}

@Composable
internal fun PackAutoUpdateCard(auto: PackAutoUpdate, onChange: (Boolean) -> Unit) {
    MCard {
        MSwitchRow(title = "Автообновление сборки", hint = auto.note, checked = auto.on, onChange = onChange)
    }
}
