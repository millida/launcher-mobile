package net.millida.launcher.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.looks.AppIcon
import net.millida.launcher.core.looks.LookAccess
import net.millida.launcher.core.looks.PremiumLook
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPlusMark
import net.millida.launcher.ui.MillidaAccents
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.PlusTier
import net.millida.launcher.ui.PremiumAccents
import net.millida.launcher.ui.home.AssetImage
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed

/**
 * Settings → Вид → «Тема лаунчера» (ThemeRow.tsx): Стандартная · PLUS ·
 * Diamond. A locked option keeps its place with a lock and leads to PLUS.
 */
@Composable
internal fun ThemeRow(theme: String, tier: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    MButtonRow(modifier) {
        PremiumLook.THEMES.forEach { option ->
            ThemeOption(
                option = option,
                selected = option == theme,
                locked = !PremiumLook.allowed(option, tier),
                onClick = { onPick(option) },
            )
        }
    }
}

@Composable
private fun ThemeOption(option: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (selected) null else UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed && !selected)
    val label = when (option) {
        PremiumLook.PLUS -> "PLUS"
        PremiumLook.DIAMOND -> "Diamond"
        else -> tr("Стандартная")
    }
    Row(
        Modifier
            .pressShift(progress)
            .height(40.dp)
            .pixelSurface(
                if (selected) colors.accentSoft else colors.surface2,
                MillidaCut.control,
                if (selected) PixelFrame(outline = colors.accent, outlineWidth = 2.dp) else PixelFrame.None,
            )
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (option) {
            PremiumLook.PLUS -> MPlusMark(tier = PlusTier.PLUS, height = 18.dp)
            PremiumLook.DIAMOND -> MPlusMark(tier = PlusTier.DIAMOND, height = 14.dp)
            else -> {
                Box(
                    Modifier
                        .size(14.dp)
                        .pixelSurface(
                            STANDARD_SWATCH,
                            0.dp,
                            PixelFrame(bottom = MillidaPixel.buttonBottom, bottomWidth = 3.dp),
                        ),
                )
                Text(label, style = MillidaType.rowTitle, color = if (selected) colors.fg else colors.fgMuted, maxLines = 1)
            }
        }
        if (locked) MIcon(MIcons.lock, size = 14.dp, color = colors.fgFaint)
    }
}

/** The standard theme's square is the default green on any accent, as `.pt-std` paints it. */
private val STANDARD_SWATCH = MillidaAccents.first().color

/** Gold and diamond in «Цвет кнопок»: shown to everyone, locked without the tier that opens them. */
@Composable
internal fun PremiumSwatches(selectedId: String, tier: String?, onPick: (String) -> Unit) {
    PremiumAccents.forEach { accent ->
        val locked = !PremiumLook.accentAllowed(accent.id, tier)
        LockableSwatch(accent.color, accent.label, accent.id == selectedId, locked) { onPick(accent.id) }
    }
}

@Composable
private fun LockableSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        ColorSwatch(color, label, selected, onClick = onClick)
        if (locked) MIcon(MIcons.lock, size = 16.dp, color = MillidaPixel.white)
    }
}

@Composable
internal fun LookGroupTitle(title: String) {
    Spacer(Modifier.height(16.dp))
    SettingHead(title)
    Spacer(Modifier.height(8.dp))
}

private val ICON_TILE_WIDTH = 84.dp
private val ICON_ART = 56.dp

/**
 * Settings → Вид → «Иконка приложения» (AppIconPicker.tsx): every icon shown
 * whole, a locked one with a lock and its condition under the name.
 */
@Composable
internal fun AppIconPicker(chosen: AppIcon, access: LookAccess, onPick: (AppIcon) -> Unit) {
    MButtonRow {
        AppIcon.entries.forEach { icon ->
            AppIconTile(icon, selected = icon == chosen, locked = !AppIcon.unlocked(icon, access)) { onPick(icon) }
        }
    }
}

@Composable
private fun AppIconTile(icon: AppIcon, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (selected) null else UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed && !selected)
    Column(
        Modifier
            .width(ICON_TILE_WIDTH)
            .pressShift(progress)
            .pixelSurface(
                if (selected) colors.accentSoft else Color.Transparent,
                MillidaCut.control,
                if (selected) PixelFrame(outline = colors.accent, outlineWidth = 2.dp) else PixelFrame.None,
            )
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = icon.title }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(ICON_ART)) {
            AssetImage("app-icons/${icon.id}.webp", Modifier.size(ICON_ART), scale = ContentScale.Fit, pixelated = false)
            if (locked) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .pixelSurface(colors.surface2, MillidaCut.badge),
                    contentAlignment = Alignment.Center,
                ) {
                    MIcon(MIcons.lock, size = 12.dp, color = colors.fgMuted)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(icon.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val note = when {
            selected -> tr("Выбрана")
            locked -> icon.need
            else -> ""
        }
        Text(
            note,
            style = MillidaType.small,
            color = if (selected) colors.accentText else colors.fgSubtle,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
