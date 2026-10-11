package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.rubies.CreatorClient
import net.millida.launcher.core.rubies.CreatorStatus
import net.millida.launcher.core.rubies.CreatorSupport
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb

private fun sinceText(iso: String): String = dayMonth(iso)?.let { "с $it" }.orEmpty()

/**
 * "Код автора" at the bottom of the shop, like Support-a-Creator. Until the
 * service has the routes the banner stays and the input honestly answers
 * "скоро заработают" instead of pretending success.
 */
@Composable
internal fun CreatorCodeCard(
    status: CreatorStatus,
    busy: Boolean,
    onApply: (code: String, onError: (String) -> Unit, onDone: () -> Unit) -> Unit,
    onRemove: () -> Unit,
    onBecome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    var editing by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val current = status.current
    val on = current != null && !editing
    MCard(
        modifier = modifier,
        background = MillidaPixel.counter.mixSrgb(colors.surface, 0.10f),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.heart, size = 18.dp, color = MillidaPixel.counter)
            Spacer(Modifier.width(8.dp))
            Text("Код автора", modifier = Modifier.weight(1f), style = MillidaType.sectionTitle, color = colors.fg, maxLines = 1)
            MTextButton("Стать автором", onClick = onBecome, color = colors.fgSubtle)
        }
        Spacer(Modifier.height(10.dp))
        if (on && current != null) {
            Supported(current)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MSecondaryButton("Сменить", onClick = { editing = true }, enabled = !busy)
                MTextButton("Убрать", onClick = onRemove, enabled = !busy, color = colors.fgSubtle)
            }
        } else {
            Text("Поддержи любимого автора", style = MillidaType.rowTitle, color = colors.fg)
            Text("Автор получает часть с твоих покупок — тебе ничего не стоит", style = MillidaType.small, color = colors.fgSubtle)
            Spacer(Modifier.height(10.dp))
            val submit = {
                val typed = code.trim()
                if (typed.isNotEmpty() && !busy) {
                    error = null
                    onApply(typed, { error = it }, {
                        editing = false
                        code = ""
                    })
                }
            }
            MField(
                value = code,
                onValueChange = { value ->
                    code = value.uppercase().take(CreatorClient.MAX_CODE)
                    error = null
                },
                placeholder = "Код автора",
                modifier = Modifier.fillMaxWidth(),
                onImeAction = submit,
                autoCorrectEnabled = false,
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MillidaType.small, color = colors.danger)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MPrimaryButton("Поддержать", onClick = submit, enabled = code.isNotBlank(), size = MButtonSize.Medium, loading = busy)
                if (editing) MTextButton("Отмена", onClick = { editing = false })
            }
        }
    }
}

@Composable
private fun Supported(current: CreatorSupport) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box { MAvatar(url = current.avatarUrl, size = 56.dp, icon = MIcons.heart) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Ты поддерживаешь", style = MillidaType.small, color = colors.fgSubtle)
            Text(
                current.name,
                style = MillidaType.rowTitle.copy(fontWeight = FontWeight.Black),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val since = sinceText(current.since)
            Text(
                listOfNotNull(current.code, since.takeIf { it.isNotEmpty() }).joinToString(" · "),
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 1,
            )
        }
    }
}
