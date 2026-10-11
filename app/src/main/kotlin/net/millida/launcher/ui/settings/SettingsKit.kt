package net.millida.launcher.ui.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MillidaAccents
import net.millida.launcher.ui.MillidaColors
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.accentById
import net.millida.launcher.ui.accentFromHex
import net.millida.launcher.ui.isCustomAccent
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import java.io.File
import java.io.IOException

private const val WALLPAPER_FOLDER = "wallpaper"
private const val LEGACY_WALLPAPER = "wallpaper.img"

internal fun LazyItemScope.stepIn(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(MillidaMotion.base, easing = MillidaMotion.steps(3)),
    placementSpec = tween(MillidaMotion.base, easing = MillidaMotion.steps(3)),
    fadeOutSpec = tween(MillidaMotion.fast, easing = MillidaMotion.steps(2)),
)

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MSectionTitle(title, Modifier.padding(start = 4.dp))
        MCard(contentPadding = 14.dp, content = content)
    }
}

@Composable
internal fun SettingHead(title: String, value: String? = null, modifier: Modifier = Modifier) {
    val colors = millida
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MillidaType.rowTitle,
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(value, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
        }
    }
}

@Composable
internal fun SettingHint(text: String, modifier: Modifier = Modifier, color: Color = millida.fgSubtle) {
    Text(
        text,
        modifier = modifier,
        style = MillidaType.small,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun AccentPicker(
    selectedId: String,
    customHex: String,
    onPick: (String) -> Unit,
    onCustom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val custom = isCustomAccent(selectedId)
    MButtonRow(modifier) {
        MillidaAccents.forEach { accent ->
            ColorSwatch(accent.color, accent.label, accent.id == selectedId) {
                if (accent.id != selectedId) onPick(accent.id)
            }
        }
        CustomSwatch(
            color = if (custom) accentById(selectedId).color else null,
            onClick = { picking = true },
        )
    }
    if (picking) {
        CustomAccentSheet(
            initial = if (custom) selectedId else customHex,
            onPick = onCustom,
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (selected) null else UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed && !selected)
    Box(
        Modifier
            .pressShift(progress)
            .size(size)
            .pixelSurface(color, MillidaCut.badge, swatchFrame(colors, selected))
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun CustomSwatch(color: Color?, onClick: () -> Unit) {
    val colors = millida
    val selected = color != null
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed)
    Box(
        Modifier
            .pressShift(progress)
            .size(44.dp)
            .pixelSurface(color ?: colors.surface2, MillidaCut.badge, swatchFrame(colors, selected))
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Свой цвет" },
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) MIcon(MIcons.plus, size = 20.dp, color = colors.fgMuted)
    }
}

private fun swatchFrame(colors: MillidaColors, selected: Boolean): PixelFrame = if (selected) {
    PixelFrame(
        outline = colors.surface,
        outlineWidth = 3.dp,
        top = colors.fg,
        topWidth = 6.dp,
        bottom = colors.fg,
        bottomWidth = 6.dp,
        left = colors.fg,
        leftWidth = 6.dp,
        right = colors.fg,
        rightWidth = 6.dp,
    )
} else {
    PixelFrame(top = MillidaPixel.buttonTop, topWidth = 3.dp, bottom = MillidaPixel.buttonBottom, bottomWidth = 4.dp)
}

private const val HUE_STEPS = 12
private const val HUE_TURN = 360f
private const val DEFAULT_CUSTOM_HUE = 130f

private val TONES = listOf(
    0.45f to 1f,
    0.65f to 0.95f,
    0.8f to 0.9f,
    0.9f to 0.78f,
    1f to 0.65f,
    1f to 0.5f,
)

private fun hexOf(color: Color): String {
    val argb = color.toArgb()
    return "#%02x%02x%02x".format((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
}

private fun hueOf(hex: String): Float {
    if (!isCustomAccent(hex)) return DEFAULT_CUSTOM_HUE
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accentFromHex(hex).color.toArgb(), hsv)
    return hsv[0]
}

@Composable
private fun CustomAccentSheet(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var hue by remember { mutableFloatStateOf(hueOf(initial)) }
    var chosen by remember { mutableStateOf(initial.takeIf(::isCustomAccent).orEmpty()) }
    var typed by remember { mutableStateOf(chosen.removePrefix("#")) }
    val choose: (String) -> Unit = { hex ->
        chosen = hex
        typed = hex.removePrefix("#")
        onPick(hex)
    }
    MBottomSheet(onDismiss = onDismiss, title = "Свой цвет") {
        SettingHead("Оттенок")
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            for (step in 0 until HUE_STEPS) {
                val stepHue = HUE_TURN * step / HUE_STEPS
                ColorSwatch(
                    color = Color.hsv(stepHue, 0.8f, 0.9f),
                    label = "Оттенок ${step + 1}",
                    selected = stepHue == hue,
                    size = 36.dp,
                ) { hue = stepHue }
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingHead("Яркость")
        Spacer(Modifier.height(8.dp))
        MButtonRow {
            TONES.forEachIndexed { index, (saturation, value) ->
                val hex = hexOf(Color.hsv(hue, saturation, value))
                ColorSwatch(
                    color = accentFromHex(hex).color,
                    label = "Яркость ${index + 1}",
                    selected = hex == chosen,
                ) { choose(hex) }
            }
        }
        Spacer(Modifier.height(16.dp))
        MField(
            value = typed,
            onValueChange = { text ->
                val clean = text.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6)
                typed = clean
                val hex = "#" + clean.lowercase()
                if (isCustomAccent(hex) && hex != chosen) choose(hex)
            },
            placeholder = "5ec64d",
            leadingIcon = MIcons.brush,
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun FoldHeader(title: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    MCard(modifier = modifier, onClick = onToggle, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.menuGear, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = millida.fg,
                maxLines = 1,
            )
            MIcon(if (open) MIcons.chevronUp else MIcons.chevronDown, size = 16.dp, color = millida.fgFaint)
        }
    }
}

@Stable
internal class TrackedAction(private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
        private set

    fun start(action: () -> Job) {
        if (running) return
        running = true
        scope.launch {
            try {
                action().join()
            } finally {
                running = false
            }
        }
    }
}

@Composable
internal fun rememberTrackedAction(): TrackedAction {
    val scope = rememberCoroutineScope()
    return remember(scope) { TrackedAction(scope) }
}

/**
 * The picked picture lives outside our storage and the grant dies with the
 * process, so it is copied in. The copy lands beside the current wallpaper
 * under a new name and only then replaces it: a failed copy leaves the old one.
 */
internal suspend fun importWallpaper(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val folder = File(context.filesDir, WALLPAPER_FOLDER)
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Нет места под картинку — освободи память и повтори")
    val part = File(folder, "incoming.part")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Картинка не открылась — выбери другую")
        input.use { source -> part.outputStream().use { target -> source.copyTo(target) } }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(part.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Это не картинка — выбери PNG или JPG")
        val target = File(folder, "wallpaper-${System.currentTimeMillis()}.img")
        if (!part.renameTo(target)) throw IOException("Картинка не сохранилась — освободи память и повтори")
        target
    } finally {
        if (part.exists()) part.delete()
    }
}

internal suspend fun discardWallpaper(context: Context, path: String) {
    if (path.isBlank()) return
    withContext(Dispatchers.IO) {
        val file = File(path).canonicalFile
        val own = File(context.filesDir, WALLPAPER_FOLDER).canonicalFile
        val legacy = File(context.filesDir, LEGACY_WALLPAPER).canonicalFile
        if (file == legacy || file.parentFile == own) file.delete()
    }
}
