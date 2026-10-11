package net.millida.launcher.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.ADD_FRIEND_KEY
import net.millida.launcher.Area
import net.millida.launcher.FriendsTab
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.blockKey
import net.millida.launcher.chatTarget
import net.millida.launcher.core.account.OfflineAuth
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.core.friends.Friend
import net.millida.launcher.core.friends.FriendNicks
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.friends.FriendRequestCard
import net.millida.launcher.friendKey
import net.millida.launcher.inviteKey
import net.millida.launcher.requestKey
import net.millida.launcher.threadKey
import net.millida.launcher.ui.social.DialogRow
import net.millida.launcher.ui.social.PersonCard
import net.millida.launcher.ui.social.SocialCaption
import net.millida.launcher.ui.social.avatarFor
import net.millida.launcher.ui.social.hoursText
import net.millida.launcher.ui.social.inboxOf
import net.millida.launcher.ui.social.inviteTabItems
import net.millida.launcher.ui.social.joinAddress
import net.millida.launcher.ui.social.rememberCallHandoff
import net.millida.launcher.ui.social.shownName
import net.millida.launcher.ui.social.statusLine

/** Those who will not answer now are folded: they must not push the playing ones off screen. */
private const val OFFLINE_FOLD = 8

private val WIDE_SEARCH_WIDTH = 260.dp

private const val SITE_PROFILE_URL = "https://millida.net/profile"

private const val TOP_RANKS = 3

private const val OFFLINE_HOURS_SHARE = 0.55f

/** "Сейчас" groups by where friends are; "Часы" ranks them by hours played, the desktop sort switch. */
private enum class FriendsView(val title: String) { NOW("Сейчас"), HOURS("Часы") }

private fun FriendsTab.title(): String = when (this) {
    FriendsTab.FRIENDS -> "Друзья"
    FriendsTab.MESSAGES -> "Чаты"
    FriendsTab.REQUESTS -> "Заявки"
    FriendsTab.INVITE -> "Пригласить"
}

private fun String.norm(): String = trim().lowercase()

private fun addCandidate(query: String, friends: List<Friend>, outgoing: List<FriendRequestCard>, own: String?): String? {
    val nick = query.trim()
    if (!OfflineAuth.validNick(nick)) return null
    val needle = nick.norm()
    if (own != null && own.norm() == needle) return null
    if (friends.any { it.nickname.norm() == needle || it.gameNick.orEmpty().norm() == needle }) return null
    if (outgoing.any { it.nickname.norm() == needle }) return null
    return nick
}

@Composable
fun FriendsScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val social by model.social.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val call = rememberCallHandoff(model, social.callHintShown)
    val search = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var showOffline by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(FriendsView.NOW) }
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<Friend?>(null) }
    var removing by remember { mutableStateOf<Friend?>(null) }
    var blocking by remember { mutableStateOf<Friend?>(null) }

    LaunchedEffect(state.millidaSignedIn) {
        if (state.millidaSignedIn) {
            model.loadFriends()
            model.social.loadBlocked()
            model.social.loadMyServers()
        }
    }
    LaunchedEffect(state.millidaSignedIn, social.tab) {
        if (state.millidaSignedIn && social.tab == FriendsTab.INVITE) model.social.loadInvites()
    }
    val friendIds = state.friends.map { it.userId }
    LaunchedEffect(friendIds) { model.social.loadHours(friendIds) }

    val friends = state.friends
    val incoming = state.friendRequests?.incoming.orEmpty()
    val outgoing = state.friendRequests?.outgoing.orEmpty()
    val failure = state.errorOf(Area.FRIENDS)
    val loading = state.isLoading(Area.FRIENDS) || (Area.FRIENDS !in state.loadedAreas && failure == null)
    val ownNick = state.details?.nickname?.takeIf { it.isNotBlank() }
        ?: state.portal?.nickname?.takeIf { it.isNotBlank() }
    val findableNick = ownNick?.takeIf { !FriendNicks.isTechnical(it) }
    val needle = query.norm()
    val visible = friends.filter {
        needle.isEmpty() || it.nickname.norm().contains(needle) || it.gameNick.orEmpty().norm().contains(needle)
    }
    val candidate = addCandidate(query, friends, outgoing, ownNick)
    val playing = visible.filter { it.playing }
    val online = visible.filter { it.online && !it.playing }
    val offline = visible.filter { !it.online && !it.playing }
    val folded = needle.isEmpty() && !showOffline && offline.size > OFFLINE_FOLD
    val subtitle = if (state.millidaSignedIn && friends.isNotEmpty()) {
        "В сети ${friends.count { it.online }} из ${friends.size}"
    } else {
        null
    }

    fun copyNick(nick: String) {
        clipboard.setText(AnnotatedString(nick))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) model.notice("Ник скопирован: $nick")
    }

    fun copyInvite(text: String, done: String) {
        clipboard.setText(AnnotatedString(text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) model.notice(done)
    }

    fun invitePlay(friend: Friend) {
        model.social.invitePlay(ChatTarget.Dm(friend.userId), friend.shownName()) { model.open(Screen.HOSTING) }
    }

    fun addFromSearch(nick: String) {
        model.social.addFriend(nick)
        query = ""
    }

    val wide = LocalWideLayout.current
    val searchField: @Composable (Modifier) -> Unit = { placement ->
        MField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Ник друга",
            modifier = placement,
            imeAction = ImeAction.Go,
            onImeAction = { if (candidate != null && visible.isEmpty()) addFromSearch(candidate) },
            focusRequester = search,
            leadingIcon = MIcons.search,
            clearable = true,
        )
    }

    MScreen(
        modifier = modifier,
        title = "Друзья",
        subtitle = subtitle,
        onBack = { model.back() },
        actions = {
            if (wide && state.millidaSignedIn && social.tab == FriendsTab.FRIENDS) {
                searchField(Modifier.width(WIDE_SEARCH_WIDTH))
            }
        },
    ) {
        if (!state.millidaSignedIn) {
            item(key = "gate") {
                MSignInGate(onSignIn = model::loginMillida, title = "Играй вместе с друзьями")
            }
            return@MScreen
        }

        item(key = "tabs") {
            MSegmented(
                options = FriendsTab.entries,
                selected = social.tab,
                onSelect = { model.social.setTab(it) },
                label = { if (it == FriendsTab.FRIENDS && friends.isNotEmpty()) "Друзья (${friends.size})" else it.title() },
                badge = {
                    when (it) {
                        FriendsTab.FRIENDS -> 0
                        FriendsTab.MESSAGES -> state.unreadMessages
                        FriendsTab.REQUESTS -> state.incomingRequests
                        FriendsTab.INVITE -> 0
                    }
                },
            )
        }

        failure?.let { message ->
            item(key = "error") {
                MErrorCard(message, onRetry = { model.retry(Area.FRIENDS) }, modifier = Modifier.animateItem())
            }
        }

        when (social.tab) {
            FriendsTab.FRIENDS -> {
                if (!wide) {
                    item(key = "search") { searchField(Modifier) }
                }
                if (friends.size > 1) {
                    item(key = "sort") {
                        MSegmented(
                            options = FriendsView.entries,
                            selected = view,
                            onSelect = { view = it },
                            label = { it.title },
                        )
                    }
                }
                if (candidate != null) {
                    item(key = "add") {
                        AddFriendCard(
                            nick = candidate,
                            busy = ADD_FRIEND_KEY in social.pending,
                            onAdd = { addFromSearch(candidate) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (loading && friends.isEmpty()) {
                    items(4, key = { "skeleton:$it" }) { MSkeletonRow() }
                } else if (friends.isEmpty() && failure == null && candidate == null) {
                    item(key = "empty") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MEmptyState(
                                icon = MIcons.users,
                                title = "Добавь первого друга",
                                actionText = "Ввести ник",
                                onAction = { runCatching { search.requestFocus() } },
                                actionIcon = MIcons.search,
                            )
                            if (findableNick != null) {
                                Text(
                                    "Тебя найдут по нику $findableNick",
                                    style = MillidaType.small,
                                    color = millida.fgFaint,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                } else if (friends.isNotEmpty() && visible.isEmpty() && candidate == null) {
                    item(key = "nobody") {
                        Text(
                            "Никого не нашли",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            style = MillidaType.body,
                            color = millida.fgFaint,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (needle.isEmpty() && !loading && social.myServers?.isEmpty() == true) {
                    item(key = "play-together") {
                        PlayTogetherCard(onCreate = { model.open(Screen.HOSTING) }, modifier = Modifier.animateItem())
                    }
                }

                fun friendRows(list: List<Friend>, ranked: Boolean) {
                    items(list, key = { "friend:" + it.userId }) { friend ->
                        FriendCard(
                            friend = friend,
                            hours = social.hours[friend.userId],
                            busy = friendKey(friend.userId) in social.pending,
                            onOpen = { model.openFriendProfile(friend.userId) },
                            rank = if (ranked) list.indexOf(friend) + 1 else null,
                            onWrite = { model.openChat(ChatTarget.Dm(friend.userId)) },
                            onJoin = friend.joinAddress()?.let { address ->
                                {
                                    model.playOnAddress(
                                        address,
                                        friend.serverName?.takeIf { it.isNotBlank() } ?: "Сервер ${friend.shownName()}",
                                    )
                                }
                            },
                            onMenu = { menuFor = friend },
                            modifier = Modifier.animateItem(),
                            onInvite = { invitePlay(friend) },
                            inviting = inviteKey(ChatTarget.Dm(friend.userId)) in social.pending,
                        )
                    }
                }

                fun section(title: String, key: String, list: List<Friend>, action: String? = null, onAction: (() -> Unit)? = null, rows: Boolean = true) {
                    if (list.isEmpty()) return
                    item(key = "cap:$key") {
                        SocialCaption("$title · ${list.size}", Modifier.animateItem(), action, onAction, actionIcon = MIcons.chevronDown)
                    }
                    if (rows) friendRows(list, ranked = false)
                }

                if (view == FriendsView.HOURS) {
                    friendRows(visible.sortedByDescending { social.hours[it.userId] ?: -1L }, ranked = true)
                } else {
                    section("Играют сейчас", "playing", playing)
                    section("В сети", "online", online)
                    section(
                        "Не в сети",
                        "offline",
                        offline,
                        action = if (folded) "Показать всех" else null,
                        onAction = if (folded) ({ showOffline = true }) else null,
                        rows = !folded,
                    )
                }

                val blocked = social.blocked
                if (needle.isEmpty()) {
                    social.blockedError?.let { message ->
                        item(key = "blocked-error") {
                            MErrorCard(message, onRetry = { model.social.loadBlocked() }, modifier = Modifier.animateItem())
                        }
                    }
                }
                if (needle.isEmpty() && blocked.isNotEmpty()) {
                    item(key = "cap:blocked") { SocialCaption("Заблокированные · ${blocked.size}", Modifier.animateItem()) }
                    items(blocked, key = { "blocked:" + it.blockedId }) { entry ->
                        PersonCard(
                            nick = entry.nickname.ifEmpty { "Игрок" },
                            avatarUrl = entry.avatarUrl,
                            status = "Заблокирован",
                            modifier = Modifier.animateItem(),
                            faded = true,
                        ) {
                            MSecondaryButton(
                                "Разблокировать",
                                onClick = { model.social.unblock(entry) },
                                size = MButtonSize.Small,
                                loading = blockKey(entry.blockedId) in social.pending,
                            )
                        }
                    }
                }

                if (ownNick != null && friends.isNotEmpty() && needle.isEmpty() && !loading) {
                    item(key = "own") {
                        if (findableNick != null) {
                            MListRow(
                                title = findableNick,
                                modifier = Modifier.animateItem(),
                                subtitle = "Твой ник для друзей",
                                leading = { MAvatar(url = avatarFor(findableNick), size = 40.dp) },
                                trailing = {
                                    MTextButton("Скопировать", onClick = { copyNick(findableNick) }, icon = MIcons.copy)
                                },
                            )
                        } else {
                            MListRow(
                                title = "Ник служебный — друзья тебя не найдут",
                                modifier = Modifier.animateItem(),
                                trailing = {
                                    MTextButton("Задать ник", onClick = { model.openLinkOrNotice(context, SITE_PROFILE_URL) }, icon = MIcons.user)
                                },
                            )
                        }
                    }
                }
            }

            FriendsTab.MESSAGES -> {
                val threads = inboxOf(state.dialogs)
                val byId = friends.associateBy { it.userId }
                if (loading && state.dialogs.isEmpty()) {
                    items(4, key = { "skeleton:$it" }) { MSkeletonRow() }
                } else if (threads.isEmpty() && failure == null) {
                    item(key = "empty") {
                        MEmptyState(
                            icon = MIcons.chat,
                            title = "Переписок нет",
                            actionText = "К друзьям",
                            onAction = { model.social.setTab(FriendsTab.FRIENDS) },
                            actionIcon = MIcons.friends,
                            primary = false,
                        )
                    }
                }
                items(threads, key = { "thread:" + threadKey(it.chatTarget()) }) { dialog ->
                    DialogRow(
                        dialog = dialog,
                        friend = if (dialog.kind == DialogKind.DM) byId[dialog.id] else null,
                        preview = social.previews[threadKey(dialog.chatTarget())],
                        onOpen = { model.openChat(dialog.chatTarget()) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            FriendsTab.REQUESTS -> {
                val nothing = incoming.isEmpty() && outgoing.isEmpty()
                if (nothing && loading) {
                    items(3, key = { "skeleton:$it" }) { MSkeletonRow() }
                } else if (nothing && failure == null) {
                    item(key = "empty") { MEmptyState(icon = MIcons.inbox, title = "Заявок нет") }
                }
                if (incoming.isNotEmpty()) {
                    item(key = "cap:incoming") { SocialCaption("Входящие · ${incoming.size}", Modifier.animateItem()) }
                    items(incoming, key = { "in:" + it.id }) { request ->
                        val busy = requestKey(request.id) in social.pending
                        PersonCard(
                            nick = request.nickname,
                            avatarUrl = request.avatarUrl,
                            status = "Хочет в друзья",
                            modifier = Modifier.animateItem(),
                        ) {
                            MPrimaryButton(
                                "Принять",
                                onClick = { model.social.accept(request.id) },
                                icon = MIcons.check,
                                size = MButtonSize.Small,
                                loading = busy,
                            )
                            MSecondaryButton(
                                "Отклонить",
                                onClick = { model.social.decline(request.id) },
                                enabled = !busy,
                                size = MButtonSize.Small,
                            )
                        }
                    }
                }
                if (outgoing.isNotEmpty()) {
                    item(key = "cap:outgoing") { SocialCaption("Отправленные · ${outgoing.size}", Modifier.animateItem()) }
                    items(outgoing, key = { "out:" + it.id }) { request ->
                        PersonCard(
                            nick = request.nickname,
                            avatarUrl = request.avatarUrl,
                            status = "Ждёт ответа",
                            modifier = Modifier.animateItem(),
                        ) {
                            MSecondaryButton(
                                "Отменить",
                                onClick = { model.social.cancel(request.id) },
                                icon = MIcons.close,
                                size = MButtonSize.Small,
                                loading = requestKey(request.id) in social.pending,
                            )
                        }
                    }
                }
            }

            FriendsTab.INVITE -> inviteTabItems(
                invites = social.invites,
                onRetry = { model.social.loadInvites() },
                onApply = { code -> model.social.applyInviteCode(code) },
                onCopy = ::copyInvite,
            )
        }
    }

    menuFor?.let { friend ->
        FriendMenu(
            friend = friend,
            onInvite = if (friend.joinAddress() != null) {
                {
                    menuFor = null
                    invitePlay(friend)
                }
            } else {
                null
            },
            onCall = {
                menuFor = null
                call(ChatTarget.Dm(friend.userId))
            },
            onProfile = {
                menuFor = null
                model.openFriendProfile(friend.userId)
            },
            onCopy = {
                menuFor = null
                copyNick(friend.shownName())
            },
            onRemove = {
                menuFor = null
                removing = friend
            },
            onBlock = {
                menuFor = null
                blocking = friend
            },
            onDismiss = { menuFor = null },
        )
    }

    removing?.let { friend ->
        MConfirmDialog(
            title = "Убрать ${friend.shownName()} из друзей?",
            text = "Переписка останется, вернуть можно новой заявкой",
            confirm = "Убрать",
            onConfirm = {
                removing = null
                model.social.remove(friend.userId)
            },
            onDismiss = { removing = null },
        )
    }

    blocking?.let { friend ->
        MConfirmDialog(
            title = "Заблокировать ${friend.shownName()}?",
            text = "Пропадёт из друзей и не сможет писать",
            confirm = "Заблокировать",
            onConfirm = {
                blocking = null
                model.social.block(friend.userId)
            },
            onDismiss = { blocking = null },
        )
    }
}

@Composable
private fun AddFriendCard(nick: String, busy: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MAvatar(url = avatarFor(nick), size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nick, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Отправим заявку", style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            MPrimaryButton("Добавить", onClick = onAdd, size = MButtonSize.Small, loading = busy)
        }
    }
}

@Composable
private fun FriendMenu(
    friend: Friend,
    onInvite: (() -> Unit)?,
    onCall: () -> Unit,
    onProfile: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    MBottomSheet(onDismiss = onDismiss, title = friend.shownName()) {
        if (onInvite != null) {
            MListRow("Позвать играть", icon = MIcons.server, onClick = onInvite, plate = false, trailing = {})
        }
        MListRow(
            "Позвонить",
            subtitle = "Откроется на сайте Millida",
            icon = MIcons.phone,
            onClick = onCall,
            plate = false,
            trailing = {},
        )
        MListRow("Профиль", icon = MIcons.user, onClick = onProfile, plate = false, trailing = {})
        MListRow("Скопировать ник", icon = MIcons.copy, onClick = onCopy, plate = false, trailing = {})
        MListRow("Убрать из друзей", icon = MIcons.trash, onClick = onRemove, plate = false, trailing = {})
        MListRow("Заблокировать", icon = MIcons.ban, onClick = onBlock, plate = false, trailing = {})
    }
}

@Composable
internal fun FriendCard(
    friend: Friend,
    hours: Long?,
    busy: Boolean,
    onWrite: () -> Unit,
    onJoin: (() -> Unit)?,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    onInvite: (() -> Unit)? = null,
    inviting: Boolean = false,
    onOpen: (() -> Unit)? = null,
    rank: Int? = null,
) {
    val colors = millida
    MCard(modifier = modifier, onClick = onOpen ?: onWrite, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) {
                Text(
                    rank.toString(),
                    modifier = Modifier.width(20.dp),
                    style = MillidaType.small.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (rank <= TOP_RANKS) colors.accent else colors.fgFaint,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
            }
            Box {
                MAvatar(
                    url = avatarFor(friend.shownName(), friend.avatarUrl),
                    size = 48.dp,
                    dimmed = !friend.online,
                )
                MCounterBadge(
                    friend.unread,
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-8).dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        friend.shownName(),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MillidaType.rowTitle,
                        color = colors.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val gameNick = friend.gameNick?.takeIf { it.isNotBlank() && it != friend.nickname }
                    if (gameNick != null) {
                        Text(
                            " · $gameNick",
                            style = MillidaType.small,
                            color = colors.fgFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (friend.online) {
                        MStatusDot(if (friend.place == FriendPlace.WEB) colors.fgFaint else colors.accent)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        friend.statusLine(),
                        style = MillidaType.small,
                        color = when {
                            friend.playing -> colors.accentText
                            friend.online -> colors.fgSubtle
                            else -> colors.fgFaint
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val build = friend.build?.takeIf { friend.playing && it.isNotBlank() }
                if (hours != null || build != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hours != null) {
                            MBadge(
                                hoursText(hours),
                                color = Rarity.ink,
                                background = if (friend.online) {
                                    Rarity.LEGENDARY.color
                                } else {
                                    Rarity.LEGENDARY.color.mixSrgb(colors.surface, OFFLINE_HOURS_SHARE)
                                },
                                icon = MIcons.clock,
                            )
                        }
                        if (build != null) {
                            MBadge(
                                build,
                                color = colors.fgMuted,
                                background = colors.surface2,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            MIconButton(MIcons.more, onClick = onMenu, enabled = !busy, label = "Ещё")
        }
        Spacer(Modifier.height(12.dp))
        MButtonRow {
            if (onJoin != null) {
                MPrimaryButton("Зайти", onClick = onJoin, icon = MIcons.login, size = MButtonSize.Small)
            } else if (onInvite != null) {
                MPrimaryButton("Позвать играть", onClick = onInvite, icon = MIcons.server, size = MButtonSize.Small, loading = inviting)
            }
            MSecondaryButton("Написать", onClick = onWrite, icon = MIcons.chat, size = MButtonSize.Small)
        }
    }
}

/**
 * Hosting is sold through friends: someone without a server of their own sees
 * one row here; with a server there is no row, "Позвать играть" on each friend
 * does the rest (src/components/friends/PlayTogether.tsx).
 */
@Composable
private fun PlayTogetherCard(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val colors = millida
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.server, size = 22.dp, color = colors.accentText)
            Spacer(Modifier.width(12.dp))
            Text(
                "Играйте вместе на своём сервере",
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        MPrimaryButton("Создать сервер", onClick = onCreate, icon = MIcons.server, size = MButtonSize.Small)
    }
}
