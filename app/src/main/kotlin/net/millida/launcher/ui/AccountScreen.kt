package net.millida.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.millida.launcher.Area
import net.millida.launcher.BuildConfig
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.MICROSOFT_LOGIN_TASK
import net.millida.launcher.Screen
import net.millida.launcher.ToastTone
import net.millida.launcher.UiState
import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.portal.AccountDetails
import net.millida.launcher.core.portal.Privacy
import net.millida.launcher.core.portal.asRubles
import net.millida.launcher.ui.social.avatarFor

private const val SITE_PROFILE = "https://millida.net/profile"
private const val SESSIONS_SHOWN = 6
private const val OFFLINE_NICK_MAX = 16

// The desktop picks the shop by the interface language; this app speaks Russian only,
// so the Russian market's reseller is the one it names.
private const val LICENCE_BUY_URL = "https://blups.me/product/minecraft"
private val LICENCE_BUY_TEXT: String get() = tr("Купить у реселлера Blups")

/** The desktop's accKindLabel (lib/format.ts). */
internal fun accountKindLabel(kind: AccountKind): String = when (kind) {
    AccountKind.OFFLINE -> tr("Офлайн-ник")
    AccountKind.MICROSOFT -> tr("Лицензия Microsoft")
    AccountKind.MILLIDA -> tr("Аккаунт Millida")
}

internal fun signInService(kind: AccountKind): String = when (kind) {
    AccountKind.MICROSOFT -> "Microsoft"
    AccountKind.MILLIDA, AccountKind.OFFLINE -> "Millida"
}

private enum class AccountUnlink(val title: String) { DISCORD("Discord"), TELEGRAM("Telegram") }

/** The two ways to play the desktop offers a game account (components/AccountChoice). */
internal enum class GameAccountWay { LICENCE, OFFLINE }

@Composable
fun AccountScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var removing by remember { mutableStateOf<Account?>(null) }
    var unlinking by remember { mutableStateOf<AccountUnlink?>(null) }
    var revoking by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
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
    val details = state.details?.takeIf { state.millidaSignedIn }

    MScreen(
        modifier = modifier,
        title = tr("Аккаунт"),
        subtitle = account?.let { accountKindLabel(it.kind) } ?: tr("Вход не выполнен"),
        onBack = { model.back() },
    ) {
        if (expired != null) {
            item(key = "expired") {
                ExpiredBanner(
                    kind = expired,
                    onLogin = { model.signIn(expired) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (account == null) {
            if (!state.millidaSignedIn) {
                item(key = "millida-entry") {
                    MillidaEntryCard(onSignIn = model::loginMillida, modifier = Modifier.animateItem())
                }
            }
            item(key = "game-entry") {
                MCard(modifier = Modifier.animateItem()) {
                    GameAccountHead()
                    GameAccountBody(state, model, onSafety = { model.openLinkOrNotice(context, SECURITY_URL) })
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
                MCard(modifier = Modifier.animateItem(), contentPadding = 12.dp) {
                    AccountsMenu(
                        state = state,
                        model = model,
                        onRemove = { removing = it },
                        onPlus = { model.open(Screen.PLUS) },
                        onAdd = { adding = true },
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
                        tr("Аккаунт заблокирован: {0}", details.banReason.ifEmpty { tr("причину скажет поддержка Millida") }),
                        onRetry = null,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    removing?.let { target ->
        AccountRemoveConfirm(target, model, onDone = { removing = null })
    }

    unlinking?.let { service ->
        MConfirmDialog(
            title = tr("Отвязать {title}?", service.title),
            text = tr("Привязать снова можно в любой момент"),
            confirm = tr("Отвязать"),
            onConfirm = {
                unlinking = null
                if (service == AccountUnlink.DISCORD) model.unlinkDiscord() else model.unlinkTelegram()
            },
            onDismiss = { unlinking = null },
        )
    }

    if (revoking) {
        MConfirmDialog(
            title = tr("Закрыть другие входы?"),
            text = tr("На других устройствах придётся войти заново"),
            confirm = tr("Закрыть"),
            onConfirm = {
                revoking = false
                model.revokeOtherSessions()
            },
            onDismiss = { revoking = false },
        )
    }

    if (adding) {
        AccountAddSheet(state, model, onDismiss = { adding = false })
    }
}

/** The desktop's sign-out confirm (AccountMenu): the question and «Выйти», nothing else. */
@Composable
internal fun AccountRemoveConfirm(target: Account, model: LauncherViewModel, onDone: () -> Unit) {
    MConfirmDialog(
        title = tr("Выйти из аккаунта «{nick}»?", target.nick),
        text = null,
        confirm = tr("Выйти"),
        onConfirm = {
            onDone()
            model.removeAccount(target)
        },
        onDismiss = onDone,
    )
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
                tr("Вход {0} истёк — войди заново", signInService(kind)),
                modifier = Modifier.weight(1f),
                style = MillidaType.body,
                color = colors.fg,
            )
        }
        Spacer(Modifier.height(12.dp))
        MPrimaryButton(tr("Войти заново"), onClick = onLogin, icon = MIcons.login)
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

/**
 * The desktop's account menu (components/AccountMenu): «Аккаунты», a row per account
 * with its kind, a tick on the active one and a way out, then «Millida Plus», always
 * shown, and «Добавить аккаунт». A licence that lost its sign-in reads «Войти заново».
 */
@Composable
internal fun AccountsMenu(
    state: UiState,
    model: LauncherViewModel,
    onRemove: (Account) -> Unit,
    onPlus: () -> Unit,
    onAdd: () -> Unit,
    onPicked: () -> Unit = {},
) {
    val colors = millida
    val active = state.account
    Text(
        tr("АККАУНТЫ"),
        modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 10.dp),
        style = MillidaType.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em),
        color = colors.fgSubtle,
    )
    if (state.accounts.isEmpty()) {
        Text(tr("Пока нет аккаунтов"), modifier = Modifier.padding(8.dp), style = MillidaType.small, color = colors.fgFaint)
    }
    state.accounts.forEach { account ->
        val current = account.id == active?.id
        val lost = current && account.kind == AccountKind.MICROSOFT && state.sessionExpired == AccountKind.MICROSOFT
        AccountMenuRow(
            account = account,
            current = current,
            lost = lost,
            onPick = {
                if (lost) {
                    onPicked()
                    model.recheckLicence(account)
                } else {
                    model.selectAccount(account)
                    model.notice(tr("Активный аккаунт: {nick}", account.nick))
                }
            },
            onRemove = { onRemove(account) },
        )
    }
    MDivider(Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    MSecondaryButton("Millida Plus", onClick = onPlus, modifier = Modifier.fillMaxWidth(), icon = MIcons.crown, size = MButtonSize.Small)
    Spacer(Modifier.height(6.dp))
    MSecondaryButton(tr("Добавить аккаунт"), onClick = onAdd, modifier = Modifier.fillMaxWidth(), icon = MIcons.plus, size = MButtonSize.Small)
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
                if (lost) tr("Войти заново") else accountKindLabel(account.kind),
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
            label = tr("Выйти из аккаунта"),
            background = Color.Transparent,
        )
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
        MSectionHeader(tr("Ник на Millida"))
        MCard {
            MField(
                value = draft,
                onValueChange = onDraft,
                placeholder = details.nickname.ifEmpty { tr("Ник") },
                leadingIcon = MIcons.user,
                imeAction = ImeAction.Done,
                onImeAction = { if (changed && details.nicknameCanChange && !busy) onSave() },
                enabled = details.nicknameCanChange,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (details.nicknameCanChange) {
                    tr("Меняется раз в {0} дней", details.nicknameCooldownDays.coerceAtLeast(14))
                } else {
                    tr("Сменить можно с {0}", details.nicknameNextChangeAt.take(10))
                },
                style = MillidaType.small,
                color = colors.fgFaint,
            )
            Spacer(Modifier.height(12.dp))
            MButtonRow {
                MPrimaryButton(
                    tr("Сохранить"),
                    onClick = onSave,
                    enabled = details.nicknameCanChange && changed && !busy,
                    size = MButtonSize.Small,
                )
                if (details.publicSlug.isNotEmpty()) {
                    MTextButton(tr("Профиль"), onClick = onProfile, icon = MIcons.externalLink)
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
        MSectionHeader(tr("Привязки"))
        MCard(contentPadding = 6.dp) {
            LinkRow(
                title = "Discord",
                hint = if (details.discordLinked) details.discordUsername.ifEmpty { tr("Привязан") } else tr("Не привязан"),
                linked = details.discordLinked,
                busy = busy,
                onLink = onLinkDiscord,
                onUnlink = { onUnlink(AccountUnlink.DISCORD) },
            )
            MDivider()
            LinkRow(
                title = "Telegram",
                hint = if (details.telegramLinked) details.telegramUsername.ifEmpty { tr("Привязан") } else tr("Не привязан"),
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
                MTextButton(tr("Отвязать"), onClick = onUnlink, enabled = !busy, color = colors.danger)
            } else {
                MSecondaryButton(tr("Привязать"), onClick = onLink, enabled = !busy, size = MButtonSize.Small)
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
            title = tr("Приватность"),
            subtitle = if (hidden == 0) tr("Всё видно друзьям и гостям") else tr("Скрыто: {hidden} из 6", hidden),
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
                MSectionTitle(tr("ПРОФИЛЬ"))
                MSwitchRow(tr("Активность"), null, privacy.showActivity) { onChange("showActivity", it) }
                MSwitchRow(tr("Друзья"), null, privacy.showFriends) { onChange("showFriends", it) }
                MSwitchRow(tr("Наигранное время"), null, privacy.showPlaytime) { onChange("showPlaytime", it) }
                Spacer(Modifier.height(8.dp))
                MSectionTitle(tr("ВИТРИНА"))
                MSwitchRow(tr("Достижения"), null, privacy.showAchievements) { onChange("showAchievements", it) }
                MSwitchRow(tr("Покупки"), null, privacy.showMarket) { onChange("showMarket", it) }
                MSwitchRow(tr("Серверы"), null, privacy.showServers) { onChange("showServers", it) }
                Spacer(Modifier.height(8.dp))
                MSectionTitle(tr("ЭМОЦИИ ВДВОЁМ · КТО МОЖЕТ ЗВАТЬ"))
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
        MSectionHeader(tr("Безопасность"))
        MCard {
            MInfoRow(
                tr("Почта"),
                when {
                    details.emailIsSynthetic -> tr("Не указана")
                    details.email.isEmpty() -> tr("Не указана")
                    details.emailVerified -> details.email
                    else -> tr("{0} · не подтверждена", details.email)
                },
                valueColor = if (!details.emailIsSynthetic && details.email.isNotEmpty() && !details.emailVerified) colors.warning else colors.fg,
            )
            MInfoRow(
                tr("Двухфакторка"),
                if (details.twoFactorEnabled) tr("Включена") else tr("Выключена"),
                valueColor = if (details.twoFactorEnabled) colors.accentText else colors.fgSubtle,
            )
            Spacer(Modifier.height(10.dp))
            MSecondaryButton(tr("Пароль и почта"), onClick = onSite, icon = MIcons.externalLink, size = MButtonSize.Small)
        }
    }
}

@Composable
private fun SessionsCard(state: UiState, busy: Boolean, onRevoke: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    Column(modifier) {
        MSectionHeader(tr("Входы"))
        MCard(contentPadding = 6.dp) {
            state.sessions.take(SESSIONS_SHOWN).forEachIndexed { index, session ->
                if (index > 0) MDivider()
                MListRow(
                    title = session.device.ifEmpty { tr("Неизвестное устройство") },
                    subtitle = listOf(session.place, session.lastSeen.take(10)).filter { it.isNotEmpty() }.joinToString(" · ")
                        .ifEmpty { null },
                    icon = MIcons.monitor,
                    plate = false,
                    trailing = {
                        if (session.current) MBadge(tr("Этот"), color = colors.accentText, background = colors.accentSoft)
                    },
                )
            }
            if (state.sessions.size > 1) {
                Spacer(Modifier.height(8.dp))
                MDangerButton(
                    tr("Закрыть другие"),
                    onClick = onRevoke,
                    enabled = !busy,
                    icon = MIcons.logout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The desktop login's first step (screens/Login): the Millida sign-in, its one line and where the password goes. */
@Composable
private fun MillidaEntryCard(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier) {
        Text(tr("Вход в Millida"), style = MillidaType.screenTitle, color = colors.fg)
        Spacer(Modifier.height(8.dp))
        Text(tr("Нет аккаунта — создастся при входе"), style = MillidaType.body.copy(fontSize = 14.sp), color = colors.fgMuted)
        Spacer(Modifier.height(20.dp))
        MButton(
            tr("Войти через аккаунт Millida"),
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            variant = MButtonVariant.Primary,
            size = MButtonSize.Large,
            icon = MIcons.shield,
        )
        Spacer(Modifier.height(14.dp))
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

@Composable
private fun GameAccountHead() {
    val colors = millida
    Text(tr("Игровой аккаунт"), style = MillidaType.screenTitle, color = colors.fg)
    Spacer(Modifier.height(8.dp))
    Text(tr("Как будешь играть?"), style = MillidaType.body.copy(fontSize = 14.sp), color = colors.fgMuted)
    Spacer(Modifier.height(20.dp))
}

/** The choice of a way to play, then the panel for the picked one, in place. */
@Composable
private fun GameAccountBody(
    state: UiState,
    model: LauncherViewModel,
    onSafety: () -> Unit,
    firstRun: Boolean = false,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    var way by rememberSaveable { mutableStateOf<GameAccountWay?>(null) }
    val picked = way
    if (picked == null) {
        AccountChoice(onPick = { way = it }, onBuy = { model.openLinkOrNotice(context, LICENCE_BUY_URL) }, firstRun = firstRun)
    } else {
        KindPanel(
            way = picked,
            state = state,
            model = model,
            onBack = { way = null },
            onDone = onDone,
            onSafety = onSafety,
        )
    }
}

/**
 * The desktop's AccountChoice: «Лицензия» first and loudest with the grass block,
 * «Офлайн» under it with what it lacks, then the quiet link to buy a licence.
 * [firstRun] takes the first-run sizes of screens/Login instead of the window's.
 */
@Composable
internal fun AccountChoice(onPick: (GameAccountWay) -> Unit, onBuy: () -> Unit, firstRun: Boolean = false) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ChoiceButton(
            onClick = { onPick(GameAccountWay.LICENCE) },
            primary = true,
            minHeight = if (firstRun) 56.dp else 60.dp,
        ) {
            MIcon(MIcons.mcGrass, size = 24.dp)
            Spacer(Modifier.width(if (firstRun) 10.dp else 12.dp))
            Text(
                tr("Лицензия"),
                style = MillidaType.button.copy(fontSize = 18.sp),
                color = millida.onAccent,
                maxLines = 1,
            )
        }
        if (BuildConfig.OFFLINE_LOGIN && BuildConfig.GUEST_LOGIN) {
            Spacer(Modifier.height(10.dp))
            ChoiceButton(
                onClick = { onPick(GameAccountWay.OFFLINE) },
                primary = false,
                minHeight = if (firstRun) 44.dp else 52.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Офлайн"), style = MillidaType.button.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = millida.fg, maxLines = 1)
                    Text(
                        tr("нет Realms и нет лицензионных серверов"),
                        style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = millida.fgSubtle,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(if (firstRun) 14.dp else 4.dp))
        MTextLink(LICENCE_BUY_TEXT, onClick = onBuy)
    }
}

/** A large pixel button holding its own content, for the two-line way buttons the kit's text button cannot carry. */
@Composable
private fun ChoiceButton(
    onClick: () -> Unit,
    primary: Boolean,
    minHeight: Dp,
    content: @Composable () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed)
    val base = if (primary) colors.accent else colors.surface2
    val fill = if (pressed) MillidaPixel.pressShade.compositeOver(base) else base
    Box(
        Modifier
            .fillMaxWidth()
            .pressShift(progress)
            .heightIn(min = minHeight)
            .pixelSurface(fill, MillidaCut.control, if (pressed) PixelFrame.ButtonPressed else PixelFrame.Button, dots = primary)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 28.dp, end = 28.dp, top = 9.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { content() }
    }
}

/**
 * The desktop's KindPanel (modals/AccountAdd): back, the way's head, then for a
 * licence the Microsoft sign-in with its code in place and the line about safety,
 * for an offline nick the field, prefilled with the Millida nick, and «Создать».
 */
@Composable
private fun KindPanel(
    way: GameAccountWay,
    state: UiState,
    model: LauncherViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onSafety: () -> Unit,
) {
    val before = remember { state.accounts.size }
    LaunchedEffect(state.accounts.size) {
        if (state.accounts.size > before) onDone()
    }
    val inlinePrompt = state.loginPrompt?.takeIf { it.inline && it.kind == AccountKind.MICROSOFT }
    Column(Modifier.fillMaxWidth()) {
        MTextButton(
            tr("Назад"),
            onClick = {
                if (inlinePrompt != null) model.cancelLogin()
                onBack()
            },
            icon = MIcons.chevronLeft,
        )
        Spacer(Modifier.height(12.dp))
        WayHead(way)
        Spacer(Modifier.height(16.dp))
        when (way) {
            GameAccountWay.OFFLINE -> OfflinePanel(state, model)
            GameAccountWay.LICENCE -> LicencePanel(state, model, onSafety)
        }
    }
}

@Composable
private fun WayHead(way: GameAccountWay) {
    val colors = millida
    Row(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.inset, MillidaCut.plate)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .pixelSurface(colors.surface2, MillidaCut.control),
            contentAlignment = Alignment.Center,
        ) {
            if (way == GameAccountWay.LICENCE) MIcon(MIcons.mcGrass, size = 24.dp) else MIcon(MIcons.user, size = 18.dp, color = colors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (way == GameAccountWay.LICENCE) tr("Лицензия Minecraft") else tr("Офлайн-аккаунт"),
                style = MillidaType.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.fg,
            )
            Text(
                if (way == GameAccountWay.LICENCE) tr("Вход через Microsoft") else tr("нет Realms и нет лицензионных серверов"),
                style = MillidaType.small.copy(fontSize = 12.sp),
                color = colors.fgSubtle,
            )
        }
    }
}

@Composable
private fun OfflinePanel(state: UiState, model: LauncherViewModel) {
    val pinned = state.accounts
        .firstOrNull { it.kind == AccountKind.MILLIDA }
        ?.nick
        ?.takeIf { state.millidaSignedIn && OfflineAuth.validNick(it) }
        .orEmpty()
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val nick = typed ?: pinned
    val carriesMillida = pinned.isNotEmpty() && nick.trim() == pinned
    val create = { model.addOffline(nick) }
    MField(
        value = nick,
        onValueChange = { typed = it.take(OFFLINE_NICK_MAX) },
        placeholder = tr("Ник в игре (латиница, 3–16)"),
        modifier = Modifier.fillMaxWidth(),
        imeAction = ImeAction.Done,
        onImeAction = { create() },
        autoCorrectEnabled = false,
    )
    if (carriesMillida) MFaintNote(tr("Ник аккаунта Millida: пускает на серверы с входом Millida"))
    Spacer(Modifier.height(12.dp))
    MPrimaryButton(tr("Создать"), onClick = { create() }, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LicencePanel(state: UiState, model: LauncherViewModel, onSafety: () -> Unit) {
    val colors = millida
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prompt = state.loginPrompt?.takeIf { it.inline && it.kind == AccountKind.MICROSOFT }
    val busy = MICROSOFT_LOGIN_TASK in state.tasks

    // As the desktop's startMsLogin: the page opens by itself and the code lands in the clipboard.
    LaunchedEffect(prompt?.code) {
        if (prompt != null) {
            clipboard.setText(AnnotatedString(prompt.code))
            model.openLinkOrNotice(context, prompt.url)
        }
    }

    if (prompt != null) {
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
    } else {
        MPrimaryButton(
            if (busy) tr("Ждём подтверждения…") else tr("Войти через Microsoft"),
            onClick = { model.loginMicrosoft(inline = true) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            icon = MIcons.shield,
        )
    }
    SafetyLine(onSafety)
    if (prompt != null) {
        MFaintNote(
            tr("Код скопирован — вставь его на странице Microsoft и подтверди вход."),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
internal fun CodeBox(code: String, onCopy: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
            .clickable(interaction, indication = null, role = Role.Button, onClick = onCopy)
            .semantics { contentDescription = tr("Скопировать код") }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            code,
            style = MillidaType.pixelLarge.copy(fontSize = 22.sp, letterSpacing = 0.14.em, fontWeight = FontWeight.Bold),
            color = colors.fg,
            maxLines = 1,
        )
        MIcon(MIcons.copy, size = 18.dp, color = colors.fgMuted)
    }
}

/** «Полностью безопасно. Убедись сам: Как это работает?» under the Microsoft sign-in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SafetyLine(onSafety: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.Center,
    ) {
        MIcon(MIcons.shield, size = 14.dp, color = colors.fgSubtle, modifier = Modifier.align(Alignment.CenterVertically))
        Text(
            tr("Полностью безопасно. Убедись сам:"),
            modifier = Modifier.align(Alignment.CenterVertically),
            style = MillidaType.small.copy(fontSize = 12.5.sp),
            color = colors.fgSubtle,
        )
        Text(
            tr("Как это работает?"),
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .heightIn(min = 32.dp)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onSafety)
                .padding(vertical = 6.dp),
            style = MillidaType.small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
            color = colors.accentText,
        )
    }
}

/**
 * The desktop's «Добавить аккаунт» window (modals/AccountAdd): the choice, the
 * panel of the picked way, and «Как это работает» in place of both when asked.
 */
@Composable
internal fun AccountAddSheet(state: UiState, model: LauncherViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var safety by rememberSaveable { mutableStateOf(false) }
    val close = {
        if (state.loginPrompt?.inline == true) model.cancelLogin()
        onDismiss()
    }
    MBottomSheet(onDismiss = close, title = if (safety) tr("Как это работает") else tr("Добавить аккаунт")) {
        if (safety) {
            SecurityInfo(onBack = { safety = false }, onSite = { model.openLinkOrNotice(context, SECURITY_URL) })
        } else {
            GameAccountBody(state, model, onSafety = { safety = true }, onDone = onDismiss)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MSecondaryButton(tr("Закрыть"), onClick = close)
        }
    }
}

/**
 * The desktop's first-run game-account step (screens/Login GameAccountStep), shown
 * right after a new player's Millida sign-in until a licence or an offline nick is
 * added. «Как это работает?» opens the page on the site there, as on the desktop.
 * There is no way past it but a game account, so Back leaves the app as there.
 */
@Composable
fun GameAccountStep(state: UiState, model: LauncherViewModel, onLeave: () -> Unit) {
    val colors = millida
    val context = LocalContext.current
    BackHandler {
        if (state.loginPrompt?.inline == true) model.cancelLogin() else onLeave()
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
            Text(
                tr("Игровой аккаунт"),
                style = MillidaType.screenTitle.copy(fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em),
                color = colors.fg,
            )
            Spacer(Modifier.height(8.dp))
            Text(tr("Как будешь играть?"), style = MillidaType.body.copy(fontSize = 14.sp), color = colors.fgMuted)
            Spacer(Modifier.height(20.dp))
            GameAccountBody(
                state,
                model,
                onSafety = { model.openLinkOrNotice(context, SECURITY_URL) },
                firstRun = true,
            )
        }
    }
}
