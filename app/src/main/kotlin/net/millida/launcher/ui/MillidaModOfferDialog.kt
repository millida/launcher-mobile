package net.millida.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Asked before starting a build that can take the mod but has none; «Без мода» is remembered per build. */
@Composable
fun MillidaModOfferDialog(onInstall: () -> Unit, onDecline: () -> Unit, onCancel: () -> Unit) {
    MDialog(
        title = "Поставить мод Millida?",
        onDismiss = onCancel,
        body = {
            Text(
                "С ним в игре работают голосовой чат, косметика — нимбы, крылья, питомцы — и эмоции. Без мода видны только скин и плащ.",
                style = MillidaType.body,
                color = millida.fgMuted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Если с модом что-то не так, его можно выключить в содержимом сборки.",
                style = MillidaType.small,
                color = millida.fgMuted,
            )
        },
        actions = {
            MTextButton("Без мода", onClick = onDecline)
            MPrimaryButton("Поставить", onClick = onInstall)
        },
    )
}
