package net.millida.launcher.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.CrashNote
import net.millida.launcher.CrashStatus
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.support.BugKind
import net.millida.launcher.core.support.BugReport
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed

private const val REVIEW_MAX = 2_000
private const val BUG_TEXT_MAX = 2_000
private val STAR_WORDS = listOf("Оценка — по желанию", "Плохо", "Так себе", "Нормально", "Хорошо", "Отлично")

@Composable
internal fun ReviewSheet(
    sending: Boolean,
    onSend: (text: String, stars: Int, onSent: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    var text by rememberSaveable { mutableStateOf("") }
    var stars by rememberSaveable { mutableIntStateOf(0) }
    val ready = text.trim().length >= BugReport.TEXT_MIN
    MBottomSheet(onDismiss = onDismiss, title = "Что думаешь о лаунчере?") {
        Spacer(Modifier.height(4.dp))
        StarPicker(stars) { stars = it }
        Spacer(Modifier.height(4.dp))
        Text(
            STAR_WORDS[stars.coerceIn(0, STAR_WORDS.lastIndex)],
            modifier = Modifier.fillMaxWidth(),
            style = MillidaType.small,
            color = colors.fgSubtle,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        MField(
            value = text,
            onValueChange = { text = it.take(REVIEW_MAX) },
            placeholder = "Что нравится, что мешает",
            singleLine = false,
            imeAction = ImeAction.Default,
        )
        ShortTextHint(text, ready)
        Spacer(Modifier.height(16.dp))
        MPrimaryButton(
            text = "Отправить",
            onClick = { onSend(text, stars, onDismiss) },
            modifier = Modifier.fillMaxWidth(),
            enabled = ready,
            icon = MIcons.send,
            size = MButtonSize.Large,
            loading = sending,
        )
    }
}

@Composable
internal fun BugSheet(
    sending: Boolean,
    onSend: (kind: BugKind, text: String, attachLog: Boolean, onSent: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(BugKind.CRASH) }
    var text by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(true) }
    val ready = text.trim().length >= BugReport.TEXT_MIN
    MBottomSheet(onDismiss = onDismiss, title = "Что сломалось?") {
        Spacer(Modifier.height(4.dp))
        MButtonRow {
            BugKind.entries.forEach { option ->
                MChip(text = option.label, selected = option == kind, onClick = { kind = option })
            }
        }
        Spacer(Modifier.height(12.dp))
        MField(
            value = text,
            onValueChange = { text = it.take(BUG_TEXT_MAX) },
            placeholder = "Что делал и что случилось",
            singleLine = false,
            imeAction = ImeAction.Default,
        )
        ShortTextHint(text, ready)
        Spacer(Modifier.height(4.dp))
        MSwitchRow("Приложить журнал", "Последняя игра, без токенов и ников", attach) { attach = it }
        Spacer(Modifier.height(12.dp))
        MPrimaryButton(
            text = "Отправить",
            onClick = { onSend(kind, text, attach, onDismiss) },
            modifier = Modifier.fillMaxWidth(),
            enabled = ready,
            icon = MIcons.send,
            size = MButtonSize.Large,
            loading = sending,
        )
    }
}

@Composable
private fun ShortTextHint(text: String, ready: Boolean) {
    if (text.isBlank() || ready) return
    Spacer(Modifier.height(6.dp))
    SettingHint("Ещё пару слов — от ${BugReport.TEXT_MIN} символов")
}

@Composable
private fun StarPicker(value: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
    ) {
        for (position in 1..5) {
            Star(position, position <= value) { onPick(position) }
        }
    }
}

@Composable
private fun Star(position: Int, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed, MillidaMotion.tilePress, MillidaMotion.steps(2))
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (on) 1f else 0.82f,
        animationSpec = tween(if (reduced) 1 else MillidaMotion.fast, easing = MillidaMotion.steps(2)),
        label = "star-pop",
    )
    Box(
        Modifier
            .size(52.dp)
            .pressShift(progress, MillidaMotion.tilePressOffset)
            .selectable(on, interaction, null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$position из 5" },
        contentAlignment = Alignment.Center,
    ) {
        MIcon(
            MIcons.star,
            size = 40.dp,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .alpha(if (on) 1f else 0.28f),
        )
    }
}

@Composable
internal fun CrashCard(
    note: CrashNote,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    MCard(
        modifier = modifier,
        contentPadding = 14.dp,
        frame = colors.plateFrame().copy(left = colors.danger, leftWidth = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.alert, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Прошлый запуск упал",
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            when (note.status) {
                CrashStatus.Sending -> MPixelLoader()
                CrashStatus.Sent -> MBadge("Отправлен", colors.accentText, colors.accentSoft, icon = MIcons.check)
                CrashStatus.Failed, CrashStatus.Kept -> MBadge("Не отправлен", colors.fgSubtle, colors.surface2)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            note.headline,
            style = MillidaType.pixelSmall,
            color = colors.fgSubtle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        MButtonRow {
            MSecondaryButton("Скопировать", onClick = onCopy, icon = MIcons.copy, size = MButtonSize.Small)
            if (note.status == CrashStatus.Kept || note.status == CrashStatus.Failed) {
                MSecondaryButton("Отправить", onClick = onSend, icon = MIcons.send, size = MButtonSize.Small)
            }
            MTextButton("Убрать", onClick = onDismiss)
        }
    }
}
