package net.millida.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.MILLIDA_LOGIN_TASK
import net.millida.launcher.ToastTone
import net.millida.launcher.UiState
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.ui.settings.LanguagePicker

private const val MANUAL_LOGIN_PAGE = "millida.net/auth/launcher"

/**
 * The desktop's Login screen: the Millida sign-in comes before the lobby, with its code
 * in place, and «Играть гостем» only for someone who signed in before. The game
 * account is picked right after, on the first-run step.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(state: UiState, model: LauncherViewModel, onLeave: () -> Unit) {
    val colors = millida
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prompt = state.loginPrompt?.takeIf { it.inline && it.kind == AccountKind.MILLIDA }
    val busy = MILLIDA_LOGIN_TASK in state.tasks

    BackHandler {
        if (busy || prompt != null) model.cancelLogin() else onLeave()
    }
    LaunchedEffect(prompt?.code) {
        if (prompt != null) {
            clipboard.setText(AnnotatedString(prompt.code))
            model.openLinkOrNotice(context, prompt.url)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .swallowTaps()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(horizontal = screenPadding, vertical = 24.dp),
        ) {
            LanguagePicker()
            Spacer(Modifier.height(14.dp))
            BrandLockup(signSize = 34.dp, color = colors.fg)
            Spacer(Modifier.height(14.dp))
            Text(
                tr("Вход в Millida"),
                style = MillidaType.screenTitle.copy(fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em),
                color = colors.fg,
            )
            Spacer(Modifier.height(8.dp))
            Text(tr("Нет аккаунта — создастся при входе"), style = MillidaType.body.copy(fontSize = 14.sp), color = colors.fgMuted)
            Spacer(Modifier.height(20.dp))
            MButton(
                if (busy) tr("Ждём подтверждения…") else tr("Войти через аккаунт Millida"),
                onClick = { model.loginMillida(inline = true) },
                modifier = Modifier.fillMaxWidth(),
                variant = MButtonVariant.Primary,
                size = MButtonSize.Large,
                enabled = !busy,
                icon = MIcons.shield,
            )
            if (prompt != null) {
                Spacer(Modifier.height(14.dp))
                CodeBox(prompt.code) {
                    clipboard.setText(AnnotatedString(prompt.code))
                    model.notice(tr("Код скопирован"), tone = ToastTone.Info)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MSecondaryButton(
                        tr("Открыть страницу"),
                        onClick = { model.openLinkOrNotice(context, prompt.url) },
                        modifier = Modifier.weight(1f),
                        size = MButtonSize.Small,
                    )
                    MButton(
                        tr("Копировать ссылку"),
                        onClick = {
                            clipboard.setText(AnnotatedString(prompt.url))
                            model.notice(tr("Ссылка скопирована"), tone = ToastTone.Info)
                        },
                        variant = MButtonVariant.Ghost,
                        size = MButtonSize.Small,
                    )
                    MButton(tr("Отмена"), onClick = model::cancelLogin, variant = MButtonVariant.Ghost, size = MButtonSize.Small)
                }
                // The browser does not always come up: the address for typing the code by hand has to be in sight.
                MFaintNote(
                    tr("Не открылось — {url}", MANUAL_LOGIN_PAGE),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
            if (state.guestAllowed) {
                Spacer(Modifier.height(12.dp))
                MSecondaryButton(
                    tr("Играть гостем"),
                    onClick = model::playAsGuest,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MIcon(MIcons.shield, size = 14.dp, color = colors.fgSubtle)
                Text(tr("Пароль — только на сайте Millida"), style = MillidaType.small, color = colors.fgSubtle)
            }
        }
    }
}
