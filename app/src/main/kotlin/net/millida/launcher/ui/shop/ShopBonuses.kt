package net.millida.launcher.ui.shop

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.rubies.DiscordReward
import net.millida.launcher.core.rubies.PlayChests
import net.millida.launcher.core.rubies.SocialStep
import net.millida.launcher.core.rubies.TelegramReward
import net.millida.launcher.core.rubies.discordInvite
import net.millida.launcher.core.rubies.discordStep
import net.millida.launcher.core.rubies.telegramLink
import net.millida.launcher.core.rubies.telegramStep
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MSecHead
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import java.time.LocalDate
import java.time.ZoneId

private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
private const val HOUR_S = 3600L
internal const val PASS_DAYS = 28

/** Play chests turn over at 00:00 MSK. */
internal fun nextMoscowMidnight(now: Long): Long =
    LocalDate.now(MOSCOW).plusDays(1).atStartOfDay(MOSCOW).toInstant().toEpochMilli().coerceAtLeast(now)

internal enum class PlaySlot { GOT, READY, NEXT, LOCKED }

internal fun playSlot(index: Int, view: PlayChests): PlaySlot = when {
    index < view.claimed -> PlaySlot.GOT
    index < view.earned -> PlaySlot.READY
    index == view.earned -> PlaySlot.NEXT
    else -> PlaySlot.LOCKED
}

/** «34 мин» / «1 ч 05 мин» of play to the next chest. */
internal fun playLeftText(seconds: Long): String {
    val minutes = maxOf(1L, (seconds + 59) / 60)
    if (minutes < 60) return tr("{minutes} мин", minutes)
    return tr("{0} ч {1} мин", minutes / 60, (minutes % 60).toString().padStart(2, '0'))
}

/**
 * «Бонусы», the desktop's at-a-glance block: today's play chests as slots —
 * «Забери» glowing, «Через 34 мин игры» with a bar, «Забрано» — with when they
 * turn over, and beside them the «Подарок дня» and «Пропуск» tiles.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BonusesBlock(
    view: PlayChests,
    viewAt: Long,
    busy: Boolean,
    giftReady: Int,
    giftBusy: Boolean,
    passDay: Int,
    onClaim: () -> Unit,
    onGift: () -> Unit,
    onPass: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val now by rememberNow(30_000L)
    val left = view.nextInS?.let { maxOf(0L, it - (SystemClock.elapsedRealtime() - viewAt) / 1000) }
    val reset = nextMoscowMidnight(now)
    val slots: @Composable (Modifier) -> Unit = { slotsModifier ->
        MCard(modifier = slotsModifier, contentPadding = 14.dp) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tr("Сундуки за игру · {claimed} из {limit}", view.claimed, view.limit),
                    style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black),
                    color = colors.fg,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.clock, size = 13.dp, color = colors.fgSubtle)
                    Spacer(Modifier.width(5.dp))
                    Text(tr("Обновится через {0}", leftLabel(maxOf(0L, reset - now))), style = MillidaType.small, color = colors.fgSubtle, maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(view.limit.coerceIn(0, MAX_SLOTS)) { index ->
                    PlaySlotView(index, playSlot(index, view), view, left, busy, onClaim, Modifier.weight(1f))
                }
            }
        }
    }
    val side: @Composable (Modifier) -> Unit = { sideModifier ->
        Column(sideModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BonusTile(
                icon = MIcons.gift,
                title = tr("Подарок дня"),
                line = if (giftReady > 0) tr("Забери {giftReady}", giftReady) else tr("Завтра новый"),
                ready = giftReady > 0,
                enabled = giftReady > 0 && !giftBusy,
                onClick = onGift,
            )
            BonusTile(
                icon = MIcons.star,
                title = tr("Пропуск"),
                line = tr("День {passDay} из {PASS_DAYS}", passDay, PASS_DAYS),
                ready = false,
                enabled = true,
                onClick = onPass,
                progress = passDay / PASS_DAYS.toFloat(),
            )
        }
    }
    if (wide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            slots(Modifier.weight(2f))
            side(Modifier.weight(1f))
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            slots(Modifier.fillMaxWidth())
            side(Modifier.fillMaxWidth())
        }
    }
}

private const val MAX_SLOTS = 6

@Composable
private fun PlaySlotView(
    index: Int,
    state: PlaySlot,
    view: PlayChests,
    left: Long?,
    busy: Boolean,
    onClaim: () -> Unit,
    modifier: Modifier,
) {
    val colors = millida
    val ready = state == PlaySlot.READY
    Column(
        modifier
            .pixelSurface(
                if (ready) colors.accent.mixSrgb(colors.surface2, 0.16f) else colors.surface2,
                MillidaCut.plate,
                if (ready) PixelFrame(outline = colors.accent, outlineWidth = 2.dp) else colors.plateFrame(),
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChestArt("COMMON", side = 56.dp, alpha = if (state == PlaySlot.GOT || state == PlaySlot.LOCKED) 0.5f else 1f)
        when (state) {
            PlaySlot.GOT -> Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.check, size = 14.dp, color = colors.accentText)
                Spacer(Modifier.width(4.dp))
                Text(tr("Забрано"), style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.accentText, maxLines = 1)
            }
            PlaySlot.READY -> MPrimaryButton(tr("Забери"), onClick = onClaim, modifier = Modifier.fillMaxWidth(), size = MButtonSize.Small, loading = busy)
            PlaySlot.NEXT, PlaySlot.LOCKED -> {
                val next = state == PlaySlot.NEXT && left != null
                Text(
                    if (next && left != null) tr("Через {0} игры", playLeftText(left)) else tr("Ещё {0} ч игры", index + 1 - view.earned),
                    style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                    color = colors.fgMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                MProgressBar(if (next && left != null) (1f - left / HOUR_S.toFloat()).coerceIn(0f, 1f) else 0f)
            }
        }
    }
}

@Composable
private fun BonusTile(
    icon: MillidaIcon,
    title: String,
    line: String,
    ready: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    progress: Float? = null,
) {
    val colors = millida
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .pixelSurface(
                if (ready) colors.accent.mixSrgb(colors.surface, 0.16f) else colors.surface,
                MillidaCut.plate,
                if (ready) PixelFrame(outline = colors.accent, outlineWidth = 2.dp) else colors.plateFrame(),
            )
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, size = 40.dp, color = if (ready) colors.accentText else Rarity.LEGENDARY.color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg, maxLines = 1)
            Text(line, style = MillidaType.small, color = if (ready) colors.accentText else colors.fgSubtle, maxLines = 1)
            if (progress != null) MProgressBar(progress.coerceIn(0f, 1f))
        }
    }
}

/** «Telegram-канал»: a one-time reward — link Telegram in the bot first, then subscribe and «Проверить». */
@Composable
internal fun TelegramBlock(view: TelegramReward?, busy: Boolean, onOpen: (String) -> Unit, onClaim: () -> Unit, modifier: Modifier = Modifier) {
    val step = telegramStep(view)
    if (view == null || step == SocialStep.HIDDEN) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MSecHead(tr("Telegram-канал"), aside = { OneTimeTag() })
        SocialRow(
            title = tr("Подпишись на канал Millida"),
            line = if (step == SocialStep.LINK) tr("Сначала привяжи Telegram в боте, потом подпишись на канал") else tr("Подпишись на канал и нажми «Проверить»"),
            amount = view.amount,
        ) {
            if (step == SocialStep.LINK) {
                val bot = telegramLink(view.botUrl)
                MPrimaryButton(tr("Привязать Telegram"), onClick = { bot?.let(onOpen) }, enabled = bot != null, icon = MIcons.telegram, size = MButtonSize.Small)
            } else {
                val channel = telegramLink(view.channelUrl)
                MSecondaryButton(tr("Подписаться"), onClick = { channel?.let(onOpen) }, enabled = channel != null, icon = MIcons.telegram, size = MButtonSize.Small)
                MPrimaryButton(tr("Проверить"), onClick = onClaim, size = MButtonSize.Small, loading = busy)
            }
        }
    }
}

/** «Вступи в Discord Millida», the pair of the Telegram reward: link Discord, join the server, «Проверить». */
@Composable
internal fun DiscordBlock(
    view: DiscordReward?,
    busy: Boolean,
    linkBusy: Boolean,
    onLink: () -> Unit,
    onOpen: (String) -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = discordStep(view)
    if (view == null || step == SocialStep.HIDDEN) return
    SocialRow(
        title = tr("Вступи в Discord Millida"),
        line = if (step == SocialStep.JOIN) tr("Зайди на сервер и нажми «Проверить»") else tr("Сначала привяжи Discord к аккаунту"),
        amount = view.amount,
        modifier = modifier,
    ) {
        if (step == SocialStep.JOIN) {
            val invite = discordInvite(view.inviteUrl)
            MSecondaryButton(tr("Вступить"), onClick = { invite?.let(onOpen) }, enabled = invite != null, icon = MIcons.users, size = MButtonSize.Small)
            MPrimaryButton(tr("Проверить"), onClick = onClaim, size = MButtonSize.Small, loading = busy)
        } else {
            MPrimaryButton(tr("Привязать Discord"), onClick = onLink, icon = MIcons.discord, size = MButtonSize.Small, loading = linkBusy)
        }
    }
}

@Composable
private fun OneTimeTag() {
    val colors = millida
    Row(
        Modifier
            .pixelSurface(colors.surface2, MillidaCut.badge)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MIcons.send, size = 13.dp, color = colors.fgSubtle)
        Spacer(Modifier.width(5.dp))
        Text(tr("Разовая награда"), style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fgSubtle, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SocialRow(
    title: String,
    line: String,
    amount: Long,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val colors = millida
    MCard(modifier = modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.ruby, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black), color = colors.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(line, style = MillidaType.small, color = colors.fgSubtle)
            }
            Spacer(Modifier.width(10.dp))
            Box {
                Text("+$amount", style = MillidaType.pixelLarge.copy(fontSize = 20.sp), color = colors.fg, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}
