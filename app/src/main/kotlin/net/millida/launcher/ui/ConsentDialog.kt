package net.millida.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.i18n.tr

const val PRIVACY_URL = "https://millida.net/legal/privacy"
const val TERMS_URL = "https://millida.net/legal/terms"

/**
 * The store build asks before the first use: without the player's consent the
 * launcher may not keep the account, the nick and the device facts it sends.
 * Leaving closes the application.
 */
@Composable
fun PersonalDataConsentDialog(model: LauncherViewModel, onAccept: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    MDialog(
        title = tr("Согласие на обработку данных"),
        onDismiss = onLeave,
        body = {
            Text(
                tr("Чтобы входить в аккаунт, сохранять сборки и чинить сбои, Millida обрабатывает ник, адрес почты, данные устройства и журналы игры. Нажимая «Принимаю», ты соглашаешься на их обработку по политике конфиденциальности."),
                style = MillidaType.body,
                color = millida.fgMuted,
            )
            Spacer(Modifier.height(10.dp))
            MTextLink(tr("Политика конфиденциальности"), onClick = { model.openLinkOrNotice(context, PRIVACY_URL) })
            Spacer(Modifier.height(6.dp))
            MTextLink(tr("Пользовательское соглашение"), onClick = { model.openLinkOrNotice(context, TERMS_URL) })
        },
        actions = {
            MTextButton(tr("Выйти"), onClick = onLeave)
            MPrimaryButton(tr("Принимаю"), onClick = onAccept)
        },
    )
}
