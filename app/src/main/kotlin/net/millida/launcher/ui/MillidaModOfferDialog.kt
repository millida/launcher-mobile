package net.millida.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import net.millida.launcher.core.i18n.tr
/** Asked before starting a build that can take the mod but has none; «Без мода» is remembered per build. */
@Composable
fun MillidaModOfferDialog(onInstall: () -> Unit, onDecline: () -> Unit, onCancel: () -> Unit) {
    MDialog(
        title = tr("Поставить мод Millida?"),
        onDismiss = onCancel,
        wide = true,
        body = {
            Text(
                tr("С ним в игре работают голосовой чат, косметика — нимбы, крылья, питомцы — и эмоции. Без мода — только скин и плащ."),
                style = MillidaType.body,
                color = millida.fgMuted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Выключить можно в содержимом сборки."),
                style = MillidaType.small,
                color = millida.fgMuted,
            )
        },
        actions = {
            MTextButton(tr("Без мода"), onClick = onDecline)
            MPrimaryButton(tr("Поставить"), onClick = onInstall)
        },
    )
}
