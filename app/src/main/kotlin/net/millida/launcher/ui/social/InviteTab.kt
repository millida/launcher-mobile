package net.millida.launcher.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.InvitesState
import net.millida.launcher.core.friends.InviteOverview
import net.millida.launcher.core.friends.InviteStatus
import net.millida.launcher.core.friends.InviteTier
import net.millida.launcher.core.friends.Referrals
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

/**
 * «Позови друга», the desktop "Пригласить" tab (src/components/friends/InviteTab.tsx):
 * the player's code and link, the ladder of rewards and the friends who came.
 */
internal fun LazyListScope.inviteTabItems(
    invites: InvitesState,
    onRetry: () -> Unit,
    onApply: (String) -> Unit,
    onCopy: (text: String, done: String) -> Unit,
) {
    val overview = invites.overview
    if (overview == null) {
        val error = invites.error
        if (error != null && !invites.loading) {
            item(key = "invite-error") { MErrorCard(error, onRetry = onRetry, modifier = Modifier.animateItem()) }
        } else {
            items(3, key = { "invite-skeleton:$it" }) { MSkeletonRow() }
        }
        return
    }
    if (overview.canApply) {
        item(key = "invite-apply") {
            ApplyCodeCard(applying = invites.applying, draft = invites.draft, onApply = onApply, modifier = Modifier.animateItem())
        }
    }
    overview.invitedBy?.let { by ->
        item(key = "invite-by") {
            Text(
                "Тебя пригласил " + (by.nickname ?: "друг"),
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = 4.dp),
                style = MillidaType.small,
                color = millida.fgSubtle,
            )
        }
    }
    item(key = "invite-code") { CodeCard(overview, onCopy, Modifier.animateItem()) }
    item(key = "invite-ladder") { LadderCard(overview, Modifier.animateItem()) }
    if (overview.friends.isEmpty()) {
        item(key = "invite-empty") {
            MEmptyState(
                icon = MIcons.gift,
                title = "Пока никого",
                modifier = Modifier.animateItem(),
                actionText = if (overview.link.isNotEmpty()) "Копировать ссылку" else null,
                onAction = if (overview.link.isNotEmpty()) ({ onCopy(overview.link, "Ссылка скопирована") }) else null,
                actionIcon = MIcons.link,
            )
        }
        return
    }
    item(key = "invite-cap") { SocialCaption("Приглашённые · ${overview.friends.size}", Modifier.animateItem()) }
    items(overview.friends.withIndex().toList(), key = { "invited:" + it.index + ":" + it.value.invitedAt }) { (_, friend) ->
        val nick = friend.nickname ?: "Игрок"
        MListRow(
            title = nick,
            modifier = Modifier.animateItem(),
            subtitle = Referrals.friendStatusText(friend, overview.needPlaySeconds),
            leading = { MAvatar(url = avatarFor(friend.nickname.orEmpty()), size = 40.dp) },
            trailing = { MIcon(statusIcon(friend.status), size = 18.dp, color = statusColor(friend.status)) },
        )
    }
}

private fun statusIcon(status: InviteStatus): MillidaIcon = when (status) {
    InviteStatus.QUALIFIED -> MIcons.check
    InviteStatus.REJECTED -> MIcons.ban
    InviteStatus.PENDING -> MIcons.clock
}

@Composable
private fun statusColor(status: InviteStatus) = when (status) {
    InviteStatus.QUALIFIED -> millida.accentText
    InviteStatus.REJECTED -> millida.fgFaint
    InviteStatus.PENDING -> millida.fgSubtle
}

@Composable
private fun CardTitle(icon: MillidaIcon, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, size = 18.dp, color = millida.accentText)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MillidaType.rowTitle, color = millida.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ApplyCodeCard(applying: Boolean, draft: String, onApply: (String) -> Unit, modifier: Modifier = Modifier) {
    var code by rememberSaveable(draft) { mutableStateOf(draft) }
    val ready = Referrals.normalizeCode(code).isNotEmpty()
    MCard(modifier = modifier, contentPadding = 14.dp) {
        CardTitle(MIcons.gift, "Есть код друга?")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MField(
                value = code,
                onValueChange = { code = it.uppercase().take(CODE_INPUT_LIMIT) },
                placeholder = "Код друга",
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Done,
                onImeAction = { if (ready && !applying) onApply(code) },
                leadingIcon = MIcons.key,
                autoCorrectEnabled = false,
            )
            Spacer(Modifier.width(8.dp))
            MPrimaryButton(
                "Ввести",
                onClick = { onApply(code) },
                enabled = ready && !applying,
                icon = MIcons.check,
                size = MButtonSize.Small,
                loading = applying,
            )
        }
    }
}

@Composable
private fun CodeCard(overview: InviteOverview, onCopy: (String, String) -> Unit, modifier: Modifier = Modifier) {
    MCard(modifier = modifier, contentPadding = 14.dp) {
        CardTitle(MIcons.users, "Пригласи друга — получи PLUS")
        Spacer(Modifier.height(12.dp))
        Text(overview.code, style = MillidaType.pixel, color = millida.fg, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        MButtonRow {
            if (overview.link.isNotEmpty()) {
                MPrimaryButton(
                    "Копировать ссылку",
                    onClick = { onCopy(overview.link, "Ссылка скопирована") },
                    icon = MIcons.link,
                    size = MButtonSize.Small,
                )
            }
            MSecondaryButton("Код", onClick = { onCopy(overview.code, "Код скопирован") }, icon = MIcons.copy, size = MButtonSize.Small)
        }
        Spacer(Modifier.height(10.dp))
        Text(Referrals.ruleText(overview), style = MillidaType.small, color = millida.fgSubtle)
    }
}

@Composable
private fun LadderCard(overview: InviteOverview, modifier: Modifier = Modifier) {
    val colors = millida
    val nextTier = overview.next?.let { next -> overview.tiers.firstOrNull { it.friends == next.friends } }
    MCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(MIcons.trophy, "Засчитано: " + Referrals.friendsText(overview.qualified), Modifier.weight(1f))
            if (overview.earnedPlusDays > 0) {
                MBadge(
                    "+" + Referrals.daysText(overview.earnedPlusDays) + " PLUS",
                    color = colors.accentText,
                    background = colors.surface2,
                )
            }
        }
        val next = overview.next
        if (next != null && nextTier != null) {
            Spacer(Modifier.height(12.dp))
            MProgressBar(Referrals.tierProgress(overview.qualified, overview.tiers))
            Spacer(Modifier.height(6.dp))
            Text(
                "Ещё " + Referrals.friendsText(next.left) + " — " + Referrals.tierRewardText(nextTier),
                style = MillidaType.small,
                color = colors.fgSubtle,
            )
        }
        if (overview.tiers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                overview.tiers.forEach { tier -> TierRow(tier) }
            }
        }
    }
}

@Composable
private fun TierRow(tier: InviteTier) {
    val colors = millida
    val ink = if (tier.reached) colors.accentText else colors.fgSubtle
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tier.friends.toString(), modifier = Modifier.widthIn(min = 28.dp), style = MillidaType.pixel, color = ink)
        MIcon(if (tier.chest != null) MIcons.chest else MIcons.crown, size = 16.dp, color = ink)
        Spacer(Modifier.width(8.dp))
        Text(
            Referrals.tierRewardText(tier),
            modifier = Modifier.weight(1f),
            style = MillidaType.small,
            color = if (tier.reached) colors.fg else colors.fgSubtle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (tier.granted) MIcon(MIcons.check, size = 16.dp, color = colors.accentText)
    }
}

private const val CODE_INPUT_LIMIT = 16
