package net.millida.launcher.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.account.Account
import net.millida.launcher.ui.AccountAddSheet
import net.millida.launcher.ui.AccountRemoveConfirm
import net.millida.launcher.ui.AccountsMenu
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.swallowTaps

internal val ACCOUNT_MENU_WIDTH = 300.dp

/**
 * The desktop's account menu (`AccountMenu.tsx`) under the lobby's account
 * plate: the same list as on the account screen, with «Millida Plus» and
 * «Добавить аккаунт». The account screen with the nick, the links and the
 * privacy stays behind «Ещё», as on the phone.
 */
@Composable
internal fun LobbyAccountMenu(
    state: UiState,
    model: LauncherViewModel,
    maxHeight: Dp,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    var removing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(
        modifier
            .width(ACCOUNT_MENU_WIDTH)
            .heightIn(max = maxHeight)
            .swallowTaps()
            .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        AccountsMenu(
            state = state,
            model = model,
            onRemove = { removing = it },
            onPlus = {
                onClose()
                model.open(Screen.PLUS)
            },
            onAdd = { adding = true },
            onPicked = onClose,
        )
    }

    removing?.let { target ->
        AccountRemoveConfirm(target, model, onDone = { removing = null })
    }

    if (adding) {
        AccountAddSheet(state, model, onDismiss = { adding = false })
    }
}
