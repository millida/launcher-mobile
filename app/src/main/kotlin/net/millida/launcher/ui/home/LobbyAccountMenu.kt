package net.millida.launcher.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.ShopSection
import net.millida.launcher.UiState
import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.AccountAddSheet
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.accountKindLabel
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.shop.PlusOffer
import net.millida.launcher.ui.social.avatarFor
import net.millida.launcher.ui.swallowTaps

internal val ACCOUNT_MENU_WIDTH = 300.dp

/**
 * The desktop's account menu (`AccountMenu.tsx`) under the lobby's account
 * plate: the accounts to switch between with a way out of each, then
 * «Millida Plus» and «Добавить аккаунт». The account screen with the nick,
 * the links and the privacy stays behind «Ещё», as on the phone.
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
    val shop by model.shop.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    val active = state.account
    val expired = state.sessionExpired

    Column(
        modifier
            .width(ACCOUNT_MENU_WIDTH)
            .heightIn(max = maxHeight)
            .swallowTaps()
            .pixelSurface(colors.surface2, MillidaCut.plate, colors.plateFrame())
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            "АККАУНТЫ",
            modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 10.dp),
            style = MillidaType.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em),
            color = colors.fgSubtle,
        )
        if (state.accounts.isEmpty()) {
            Text(
                "Пока нет аккаунтов",
                modifier = Modifier.padding(8.dp),
                style = MillidaType.small,
                color = colors.fgFaint,
            )
        }
        state.accounts.forEach { account ->
            val current = account.id == active?.id
            val lost = current && expired == account.kind
            AccountMenuRow(
                account = account,
                current = current,
                lost = lost,
                onPick = {
                    model.selectAccount(account)
                    if (lost) {
                        onClose()
                        if (account.kind == AccountKind.MICROSOFT) model.loginMicrosoft() else model.loginMillida()
                    } else if (!current) {
                        model.notice("Активный аккаунт: ${account.nick}")
                    }
                },
                onRemove = { removing = account },
            )
        }
        MDivider(Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        if (state.millidaSignedIn) {
            MSecondaryButton(
                "Millida Plus",
                onClick = {
                    if (shop.plus.data == null) model.shop.retry(ShopSection.PLUS)
                    if (shop.daily.data == null) model.shop.retry(ShopSection.DAILY)
                    plusOpen = true
                },
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.crown,
                size = MButtonSize.Small,
            )
            Spacer(Modifier.height(6.dp))
        }
        MSecondaryButton(
            "Добавить аккаунт",
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth(),
            icon = MIcons.plus,
            size = MButtonSize.Small,
        )
    }

    removing?.let { target ->
        MConfirmDialog(
            title = "Выйти из аккаунта «${target.nick}»?",
            text = if (target.kind == AccountKind.MILLIDA) {
                "Друзья, кошелёк и скины будут недоступны до нового входа"
            } else {
                "Вернуть можно новым входом"
            },
            confirm = "Выйти",
            onConfirm = {
                removing = null
                model.removeAccount(target)
            },
            onDismiss = { removing = null },
        )
    }

    if (adding) {
        AccountAddSheet(state, model, onDismiss = { adding = false })
    }

    if (plusOpen) {
        MBottomSheet(onDismiss = { plusOpen = false }, title = "Подписка PLUS") {
            PlusOffer(
                plus = shop.plus.data,
                daily = shop.daily.data,
                busy = shop.isBusy("plus"),
                onSubscribe = model.shop::subscribePlus,
                onManage = model.shop::managePlus,
            )
        }
    }
}

@Composable
private fun AccountMenuRow(
    account: Account,
    current: Boolean,
    lost: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (current && !lost) null else UiSoundEvent.Nav)
    val fill = when {
        current -> colors.accentSoft
        pressed -> colors.surface
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .pixelSurface(fill, MillidaCut.control)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onPick)
            .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        MAvatar(url = avatarFor(account.nick), size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                account.nick,
                style = MillidaType.rowTitle.copy(fontSize = 13.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (lost) "Войти заново" else accountKindLabel(account.kind),
                style = MillidaType.small.copy(fontSize = 11.sp, fontWeight = if (lost) FontWeight.Bold else FontWeight.Normal),
                color = if (lost) colors.danger else colors.fgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) MIcon(MIcons.check, size = 16.dp, color = colors.accent)
        MIconButton(
            MIcons.trash,
            onClick = onRemove,
            label = "Выйти из аккаунта ${account.nick}",
            background = Color.Transparent,
        )
    }
}
