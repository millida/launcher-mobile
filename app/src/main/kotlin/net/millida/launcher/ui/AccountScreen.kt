package net.millida.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.portal.AccountDetails
import net.millida.launcher.core.portal.Privacy
import net.millida.launcher.core.portal.asRubles
import net.millida.launcher.ui.social.avatarFor

private const val SITE_PROFILE = "https://millida.net/profile"
private const val SESSIONS_SHOWN = 6
private const val BUY_LICENSE_URL = "https://blups.me/product/minecraft"

internal fun accountKindLabel(kind: AccountKind): String = when (kind) {
    AccountKind.OFFLINE -> "Без лицензии"
    AccountKind.MICROSOFT -> "Лицензия Microsoft"
    AccountKind.MILLIDA -> "Аккаунт Millida"
}

private enum class AccountUnlink(val title: String) { DISCORD("Discord"), TELEGRAM("Telegram") }

@Composable
fun AccountScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var removing by remember { mutableStateOf<Account?>(null) }
    var unlinking by remember { mutableStateOf<AccountUnlink?>(null) }
    var revoking by remember { mutableStateOf(false) }
    var offlineSheet by rememberSaveable { mutableStateOf(false) }
    var privacyOpen by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy != null

    // A binding link is opened once and then forgotten: one that reopened on
    // every recomposition would drag the player to the browser out of nowhere.
    LaunchedEffect(state.linkUrl) {
        if (state.linkUrl.isNotEmpty()) {
            model.openLinkOrNotice(context, state.linkUrl)
            model.consumeLink()
        }
    }
    LaunchedEffect(state.millidaSignedIn) {
        if (state.millidaSignedIn) model.loadAccountDetails()
    }

    val account = state.account
    val expired = state.sessionExpired?.takeIf { it == account?.kind }
    val hasMillida = state.accounts.any { it.kind == AccountKind.MILLIDA }
    val details = state.details?.takeIf { state.millidaSignedIn }

    MScreen(
        modifier = modifier,
        title = "Аккаунт",
        subtitle = account?.let { accountKindLabel(it.kind) } ?: "Вход не выполнен",
        onBack = { model.back() },
    ) {
        if (expired != null) {
            item(key = "expired") {
                ExpiredBanner(
                    kind = expired,
                    onLogin = { if (expired == AccountKind.MICROSOFT) model.loginMicrosoft() else model.loginMillida() },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (account == null) {
            item(key = "entry") {
                Column {
                    AccountEntrySection(
                        offlineNick = state.offlineNick,
                        onOfflineNick = model::setOfflineNick,
                        onMillida = model::loginMillida,
                        onMicrosoft = model::loginMicrosoft,
                        onOffline = { model.useOffline() },
                    )
                }
            }
        } else {
            item(key = "current") {
                CurrentAccount(
                    account = account,
                    avatarUrl = state.portal?.avatarUrl?.takeIf { it.isNotBlank() && account.kind == AccountKind.MILLIDA },
                    balance = state.portal?.takeIf { account.kind == AccountKind.MILLIDA }?.balanceKopecks?.asRubles(),
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "accounts") {
                AccountsCard(
                    accounts = state.accounts,
                    active = account,
                    onSelect = { model.selectAccount(it) },
                    onRemove = { removing = it },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "add") {
                Column(Modifier.animateItem()) {
                    MSectionHeader("Добавить аккаунт")
                    AddAccountOptions(
                        hasMillida = hasMillida,
                        onMillida = model::loginMillida,
                        onMicrosoft = model::loginMicrosoft,
                        onOffline = { offlineSheet = true },
                        onBuy = { model.openLinkOrNotice(context, BUY_LICENSE_URL) },
                    )
                }
            }
        }

        if (state.millidaSignedIn) {
            state.errorOf(Area.ACCOUNT)?.let { message ->
                item(key = "error") {
                    MErrorCard(message, onRetry = { model.retry(Area.ACCOUNT) }, modifier = Modifier.animateItem())
                }
            }
            if (details == null && state.isLoading(Area.ACCOUNT)) {
                items(3, key = { "skeleton:$it" }) { MSkeletonRow() }
            }
        }

        if (details != null) {
            item(key = "nick") {
                NicknameCard(
                    details = details,
                    draft = state.nicknameDraft,
                    onDraft = model::setNicknameDraft,
                    onSave = { model.changeNickname() },
                    onProfile = { model.openLinkOrNotice(context, "https://millida.net/u/${details.publicSlug}") },
                    busy = busy,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "links") {
                LinksCard(
                    details = details,
                    busy = busy,
                    onLinkDiscord = { model.linkDiscord() },
                    onLinkTelegram = { model.linkTelegram() },
                    onUnlink = { unlinking = it },
                    modifier = Modifier.animateItem(),
                )
            }
            state.privacy?.let { privacy ->
                item(key = "privacy") {
                    PrivacyCard(
                        privacy = privacy,
                        open = privacyOpen,
                        onToggleOpen = { privacyOpen = !privacyOpen },
                        onChange = { field, value -> model.togglePrivacy(field, value) },
                        onDuoInvites = model::setDuoInvites,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item(key = "security") {
                SecurityCard(
                    details = details,
                    onSite = { model.openLinkOrNotice(context, SITE_PROFILE) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.sessions.isNotEmpty()) {
                item(key = "sessions") {
                    SessionsCard(
                        state = state,
                        busy = busy,
                        onRevoke = { revoking = true },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (details.banned) {
                item(key = "banned") {
                    MErrorCard(
                        "Аккаунт заблокирован: " + details.banReason.ifEmpty { "причину скажет поддержка Millida" },
                        onRetry = null,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    removing?.let { target ->
        MConfirmDialog(
            title = "Убрать ${target.nick}?",
            text = if (target.kind == AccountKind.MILLIDA) {
                "Друзья, кошелёк и скины будут недоступны до нового входа"
            } else {
                "Вернуть можно новым входом"
            },
            confirm = "Убрать",
            onConfirm = {
                removing = null
                model.removeAccount(target)
            },
            onDismiss = { removing = null },
        )
    }

    unlinking?.let { service ->
        MConfirmDialog(
            title = "Отвязать ${service.title}?",
            text = "Привязать снова можно в любой момент",
            confirm = "Отвязать",
            onConfirm = {
                unlinking = null
                if (service == AccountUnlink.DISCORD) model.unlinkDiscord() else model.unlinkTelegram()
            },
            onDismiss = { unlinking = null },
        )
    }

    if (revoking) {
        MConfirmDialog(
            title = "Закрыть другие входы?",
            text = "На других устройствах придётся войти заново",
            confirm = "Закрыть",
            onConfirm = {
                revoking = false
                model.revokeOtherSessions()
            },
            onDismiss = { revoking = false },
        )
    }

    if (offlineSheet) {
        MBottomSheet(onDismiss = { offlineSheet = false }, title = "Игра без лицензии") {
            OfflineEntry(
                nick = state.offlineNick,
                onNick = model::setOfflineNick,
                onPlay = {
                    val valid = OfflineAuth.validNick(state.offlineNick.trim())
                    model.useOffline()
                    if (valid) offlineSheet = false
                },
            )
        }
    }
}

@Composable
private fun ExpiredBanner(kind: AccountKind, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(
        modifier = modifier,
        frame = colors.plateFrame().copy(left = colors.warning, leftWidth = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.alert, size = 20.dp, color = colors.warning)
            Spacer(Modifier.width(10.dp))
            Text(
                if (kind == AccountKind.MICROSOFT) "Вход Microsoft истёк — войди заново" else "Вход Millida истёк — войди заново",
                modifier = Modifier.weight(1f),
                style = MillidaType.body,
                color = colors.fg,
            )
        }
        Spacer(Modifier.height(12.dp))
        MPrimaryButton("Войти заново", onClick = onLogin, icon = MIcons.login)
    }
}

@Composable
private fun CurrentAccount(account: Account, avatarUrl: String?, balance: String?, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MAvatar(
                url = avatarFor(account.nick, avatarUrl),
                size = 64.dp,
                plus = account.kind == AccountKind.MILLIDA,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.nick,
                    style = MillidaType.pixelLarge,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MBadge(
                        accountKindLabel(account.kind),
                        color = if (account.kind == AccountKind.OFFLINE) colors.fgMuted else colors.accentText,
                        background = if (account.kind == AccountKind.OFFLINE) colors.surface2 else colors.accentSoft,
                        icon = if (account.kind == AccountKind.OFFLINE) MIcons.user else MIcons.shield,
                    )
                    if (balance != null) {
                        MBadge(balance, color = colors.fgMuted, background = colors.surface2, icon = MIcons.wallet)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountsCard(
    accounts: List<Account>,
    active: Account,
    onSelect: (Account) -> Unit,
    onRemove: (Account) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    Column(modifier) {
        MSectionHeader("Аккаунты")
        MCard(contentPadding = 6.dp) {
            accounts.forEachIndexed { index, entry ->
                if (index > 0) MDivider()
                val current = entry.id == active.id
                MListRow(
                    title = entry.nick,
                    subtitle = accountKindLabel(entry.kind),
                    onClick = if (current) null else ({ onSelect(entry) }),
                    plate = false,
                    leading = { MAvatar(url = avatarFor(entry.nick), size = 40.dp) },
                    trailing = {
                        if (current) MBadge("Сейчас", color = colors.accentText, background = colors.accentSoft)
                        MIconButton(MIcons.trash, onClick = { onRemove(entry) }, label = "Убрать ${entry.nick}")
                    },
                )
            }
        }
    }
}

@Composable
private fun NicknameCard(
    details: AccountDetails,
    draft: String,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onProfile: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val changed = draft.trim().isNotEmpty() && draft.trim() != details.nickname
    Column(modifier) {
        MSectionHeader("Ник на Millida")
        MCard {
            MField(
                value = draft,
                onValueChange = onDraft,
                placeholder = details.nickname.ifEmpty { "Ник" },
                leadingIcon = MIcons.user,
                imeAction = ImeAction.Done,
                onImeAction = { if (changed && details.nicknameCanChange && !busy) onSave() },
                enabled = details.nicknameCanChange,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (details.nicknameCanChange) {
                    "Меняется раз в ${details.nicknameCooldownDays.coerceAtLeast(14)} дней"
                } else {
                    "Сменить можно с ${details.nicknameNextChangeAt.take(10)}"
                },
                style = MillidaType.small,
                color = colors.fgFaint,
            )
            Spacer(Modifier.height(12.dp))
            MButtonRow {
                MPrimaryButton(
                    "Сохранить",
                    onClick = onSave,
                    enabled = details.nicknameCanChange && changed && !busy,
                    size = MButtonSize.Small,
                )
                if (details.publicSlug.isNotEmpty()) {
                    MTextButton("Профиль", onClick = onProfile, icon = MIcons.externalLink)
                }
            }
        }
    }
}

@Composable
private fun LinksCard(
    details: AccountDetails,
    busy: Boolean,
    onLinkDiscord: () -> Unit,
    onLinkTelegram: () -> Unit,
    onUnlink: (AccountUnlink) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        MSectionHeader("Привязки")
        MCard(contentPadding = 6.dp) {
            LinkRow(
                title = "Discord",
                hint = if (details.discordLinked) details.discordUsername.ifEmpty { "Привязан" } else "Не привязан",
                linked = details.discordLinked,
                busy = busy,
                onLink = onLinkDiscord,
                onUnlink = { onUnlink(AccountUnlink.DISCORD) },
            )
            MDivider()
            LinkRow(
                title = "Telegram",
                hint = if (details.telegramLinked) details.telegramUsername.ifEmpty { "Привязан" } else "Не привязан",
                linked = details.telegramLinked,
                busy = busy,
                onLink = onLinkTelegram,
                onUnlink = { onUnlink(AccountUnlink.TELEGRAM) },
            )
        }
    }
}

@Composable
private fun LinkRow(
    title: String,
    hint: String,
    linked: Boolean,
    busy: Boolean,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    val colors = millida
    MListRow(
        title = title,
        subtitle = hint,
        icon = MIcons.link,
        plate = false,
        trailing = {
            if (linked) {
                MTextButton("Отвязать", onClick = onUnlink, enabled = !busy, color = colors.danger)
            } else {
                MSecondaryButton("Привязать", onClick = onLink, enabled = !busy, size = MButtonSize.Small)
            }
        },
    )
}

@Composable
private fun PrivacyCard(
    privacy: Privacy,
    open: Boolean,
    onToggleOpen: () -> Unit,
    onChange: (String, Boolean) -> Unit,
    onDuoInvites: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val hidden = listOf(
        privacy.showActivity,
        privacy.showFriends,
        privacy.showPlaytime,
        privacy.showAchievements,
        privacy.showMarket,
        privacy.showServers,
    ).count { !it }
    MCard(modifier = modifier, contentPadding = 6.dp) {
        MListRow(
            title = "Приватность",
            subtitle = if (hidden == 0) "Всё видно друзьям и гостям" else "Скрыто: $hidden из 6",
            icon = MIcons.eye,
            onClick = onToggleOpen,
            plate = false,
            trailing = {
                MIcon(if (open) MIcons.chevronUp else MIcons.chevronDown, size = 16.dp, color = colors.fgFaint)
            },
        )
        if (open) {
            MDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            ) {
                MSectionTitle("ПРОФИЛЬ")
                MSwitchRow("Активность", null, privacy.showActivity) { onChange("showActivity", it) }
                MSwitchRow("Друзья", null, privacy.showFriends) { onChange("showFriends", it) }
                MSwitchRow("Наигранное время", null, privacy.showPlaytime) { onChange("showPlaytime", it) }
                Spacer(Modifier.height(8.dp))
                MSectionTitle("ВИТРИНА")
                MSwitchRow("Достижения", null, privacy.showAchievements) { onChange("showAchievements", it) }
                MSwitchRow("Покупки", null, privacy.showMarket) { onChange("showMarket", it) }
                MSwitchRow("Серверы", null, privacy.showServers) { onChange("showServers", it) }
                Spacer(Modifier.height(8.dp))
                MSectionTitle("ЭМОЦИИ ВДВОЁМ · КТО МОЖЕТ ЗВАТЬ")
                Spacer(Modifier.height(8.dp))
                MButtonRow {
                    Privacy.DUO_INVITES.forEach { (value, label) ->
                        MChip(text = label, selected = privacy.duoInvites == value, onClick = { onDuoInvites(value) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityCard(details: AccountDetails, onSite: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Column(modifier) {
        MSectionHeader("Безопасность")
        MCard {
            MInfoRow(
                "Почта",
                when {
                    details.emailIsSynthetic -> "Не указана"
                    details.email.isEmpty() -> "Не указана"
                    details.emailVerified -> details.email
                    else -> details.email + " · не подтверждена"
                },
                valueColor = if (!details.emailIsSynthetic && details.email.isNotEmpty() && !details.emailVerified) colors.warning else colors.fg,
            )
            MInfoRow(
                "Двухфакторка",
                if (details.twoFactorEnabled) "Включена" else "Выключена",
                valueColor = if (details.twoFactorEnabled) colors.accentText else colors.fgSubtle,
            )
            Spacer(Modifier.height(10.dp))
            MSecondaryButton("Пароль и почта", onClick = onSite, icon = MIcons.externalLink, size = MButtonSize.Small)
        }
    }
}

@Composable
private fun SessionsCard(state: UiState, busy: Boolean, onRevoke: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Column(modifier) {
        MSectionHeader("Входы")
        MCard(contentPadding = 6.dp) {
            state.sessions.take(SESSIONS_SHOWN).forEachIndexed { index, session ->
                if (index > 0) MDivider()
                MListRow(
                    title = session.device.ifEmpty { "Неизвестное устройство" },
                    subtitle = listOf(session.place, session.lastSeen.take(10)).filter { it.isNotEmpty() }.joinToString(" · ")
                        .ifEmpty { null },
                    icon = MIcons.monitor,
                    plate = false,
                    trailing = {
                        if (session.current) MBadge("Этот", color = colors.accentText, background = colors.accentSoft)
                    },
                )
            }
            if (state.sessions.size > 1) {
                Spacer(Modifier.height(8.dp))
                MDangerButton(
                    "Закрыть другие",
                    onClick = onRevoke,
                    enabled = !busy,
                    icon = MIcons.logout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OfflineEntry(nick: String, onNick: (String) -> Unit, onPlay: () -> Unit) {
    val colors = millida
    MField(
        value = nick,
        onValueChange = onNick,
        placeholder = "Ник, 3–16 знаков",
        leadingIcon = MIcons.user,
        imeAction = ImeAction.Done,
        onImeAction = onPlay,
    )
    Spacer(Modifier.height(8.dp))
    Text("Только на серверах Millida", style = MillidaType.small, color = colors.fgFaint)
    Spacer(Modifier.height(14.dp))
    MPrimaryButton(
        "Играть без лицензии",
        onClick = onPlay,
        enabled = nick.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Signing in must not wait for whatever the launcher does in the background:
 * a second tap is safe, loginMillida and loginMicrosoft cancel the previous try.
 */
@Composable
internal fun ColumnScope.AccountEntrySection(
    offlineNick: String,
    onOfflineNick: (String) -> Unit,
    onMillida: () -> Unit,
    onMicrosoft: () -> Unit,
    onOffline: () -> Unit,
) {
    val colors = millida
    MCard {
        Text("Войди, чтобы играть", style = MillidaType.sectionTitle, color = colors.fg)
        Spacer(Modifier.height(14.dp))
        MPrimaryButton("Войти через Millida", onClick = onMillida, icon = MIcons.login, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        MSecondaryButton("Лицензия Microsoft", onClick = onMicrosoft, icon = MIcons.key, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(10.dp))
    MCard {
        MSectionTitle("БЕЗ ЛИЦЕНЗИИ")
        Spacer(Modifier.height(10.dp))
        OfflineEntry(nick = offlineNick, onNick = onOfflineNick, onPlay = onOffline)
    }
}

@Composable
private fun AddAccountOptions(
    hasMillida: Boolean,
    onMillida: () -> Unit,
    onMicrosoft: () -> Unit,
    onOffline: () -> Unit,
    onBuy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hasMillida) {
            AddAccountRow("Аккаунт Millida", "Друзья, ник и баланс", MIcons.key, onClick = onMillida)
        }
        AddAccountRow("Лицензия Microsoft", "Лицензионные серверы, свой скин", MIcons.shield, onClick = onMicrosoft)
        AddAccountRow("Офлайн-аккаунт", "Только ник, без лицензии", MIcons.user, onClick = onOffline)
        AddAccountRow(
            "Купить лицензию",
            "Ключ Minecraft на Blups",
            MIcons.menuBag,
            onClick = onBuy,
            external = true,
        )
    }
}

/**
 * The desktop's «Добавить аккаунт» window, opened from the lobby's account
 * menu: the same ways in as on the account screen, and the offline nick in
 * place of the list once that way is picked.
 */
@Composable
internal fun AccountAddSheet(state: UiState, model: LauncherViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var offline by rememberSaveable { mutableStateOf(false) }
    val before = remember { state.accounts.size }
    LaunchedEffect(state.accounts.size) {
        if (state.accounts.size > before) onDismiss()
    }
    MBottomSheet(onDismiss = onDismiss, title = if (offline) "Игра без лицензии" else "Добавить аккаунт") {
        if (offline) {
            OfflineEntry(
                nick = state.offlineNick,
                onNick = model::setOfflineNick,
                onPlay = { model.useOffline() },
            )
        } else {
            AddAccountOptions(
                hasMillida = state.accounts.any { it.kind == AccountKind.MILLIDA },
                onMillida = model::loginMillida,
                onMicrosoft = model::loginMicrosoft,
                onOffline = { offline = true },
                onBuy = { model.openLinkOrNotice(context, BUY_LICENSE_URL) },
            )
        }
    }
}

@Composable
private fun AddAccountRow(title: String, subtitle: String, icon: MillidaIcon, onClick: () -> Unit, external: Boolean = false) {
    MListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick,
        trailing = {
            MIcon(if (external) MIcons.externalLink else MIcons.chevronRight, size = 18.dp, color = millida.fgFaint)
        },
    )
}
