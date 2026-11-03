package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.rubies.CreatorClient
import net.millida.launcher.core.rubies.CreatorStatus
import net.millida.launcher.ui.MAvatar
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.mixSrgb
import net.millida.launcher.ui.pixelSurface

/** The desktop's banner frame under the shop: art on the left, a big title, one line and one row of action. */
@Composable
private fun Banner(
    tone: Color,
    art: @Composable () -> Unit,
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    row: @Composable () -> Unit,
) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .pixelSurface(
                tone.mixSrgb(colors.surface, 0.14f),
                MillidaCut.plate,
                PixelFrame(outline = tone.mixSrgb(colors.surface, 0.6f), outlineWidth = 2.dp, bottom = MillidaPixel.tileBottom, bottomWidth = 5.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) { art() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MillidaType.sectionTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.Black),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(line, style = MillidaType.small, color = colors.fgMuted)
            Spacer(Modifier.height(2.dp))
            row()
        }
    }
}

@Composable
private fun ArtTrio(big: MillidaIcon, small: MillidaIcon, tone: Color) {
    Box(Modifier.size(84.dp)) {
        MIcon(big, size = 64.dp, color = tone, modifier = Modifier.align(Alignment.Center))
        MIcon(small, size = 28.dp, color = millida.fg, modifier = Modifier.align(Alignment.BottomEnd))
        MIcon(MIcons.sparkle, size = 20.dp, color = Rarity.LEGENDARY.color, modifier = Modifier.align(Alignment.TopStart))
    }
}

/** «Стань автором»: the pair of «Поддержи автора», it leads to the creators' page on the site. */
@Composable
internal fun CreatorBecomeBanner(onBecome: () -> Unit, modifier: Modifier = Modifier) {
    Banner(
        tone = Rarity.LEGENDARY.color,
        art = { ArtTrio(MIcons.star, MIcons.camera, Rarity.LEGENDARY.color) },
        title = tr("Стань автором"),
        line = tr("Получай 50% с покупок по твоему коду"),
        modifier = modifier,
    ) {
        MPrimaryButton(tr("Хочу"), onClick = onBecome, icon = MIcons.externalLink)
    }
}

/**
 * «Поддержи автора», one compact banner: the code field and «Ок»; once a code
 * is set, the creator's face and name with a cross to remove it. Until the
 * service has the routes the input honestly answers instead of pretending.
 */
@Composable
internal fun CreatorSupportBanner(
    status: CreatorStatus?,
    busy: Boolean,
    onApply: (code: String, onError: (String) -> Unit, onDone: () -> Unit) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val current = status?.current
    Banner(
        tone = MillidaPixel.counter,
        art = {
            if (current != null) {
                MAvatar(url = current.avatarUrl, size = 72.dp, icon = MIcons.heart)
            } else {
                ArtTrio(MIcons.heart, MIcons.user, MillidaPixel.counter)
            }
        },
        title = current?.name ?: tr("Поддержи автора"),
        line = tr("Автор получит деньги с твоих покупок"),
        modifier = modifier,
    ) {
        if (current != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.heart, size = 20.dp, color = MillidaPixel.counter)
                MIconButton(MIcons.close, onClick = onRemove, enabled = !busy, label = tr("Убрать код"))
            }
        } else {
            val submit = {
                val typed = code.trim()
                if (typed.isNotEmpty() && !busy) {
                    error = null
                    onApply(typed, { error = it }, { code = "" })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MField(
                    value = code,
                    onValueChange = { value ->
                        code = value.uppercase().take(CreatorClient.MAX_CODE)
                        error = null
                    },
                    placeholder = tr("КОД"),
                    modifier = Modifier.weight(1f),
                    onImeAction = submit,
                    autoCorrectEnabled = false,
                )
                MPrimaryButton(tr("Ок"), onClick = submit, enabled = code.isNotBlank(), size = MButtonSize.Medium, loading = busy)
            }
            error?.let { Text(it, style = MillidaType.small, color = colors.danger) }
        }
    }
}
