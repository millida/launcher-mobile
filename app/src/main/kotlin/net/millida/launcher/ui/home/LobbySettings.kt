package net.millida.launcher.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.media.LiveWallpaperKind
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MSlider
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.wellFrame
import net.millida.launcher.ui.settings.SettingHead
import net.millida.launcher.ui.settings.SettingHint
import net.millida.launcher.ui.settings.SettingsSection
import kotlin.math.roundToInt

/** The lobby's music and its guide, the desktop's «Звук» and «Гайд» rows. */
@Composable
fun LobbySettingsSection(model: LauncherViewModel, modifier: Modifier = Modifier) {
    val ambient by model.ambient.state.collectAsStateWithLifecycle()
    var volume by remember(ambient.musicVolume) { mutableIntStateOf(ambient.musicVolume) }

    SettingsSection("ЛОББИ", modifier) {
        MSwitchRow("Музыка сейчас", ambient.nowPlaying.takeIf { ambient.playing }, ambient.musicOn, model.ambient::setMusicOn)
        ambient.musicError?.takeIf { ambient.musicOn }?.let { SettingHint(it, color = millida.warning) }
        if (ambient.musicOn) {
            MDivider()
            MSwitchRow("Включать при запуске", null, ambient.musicAtStart, model.ambient::setMusicAtStart)
            Spacer(Modifier.height(10.dp))
            SettingHead("Громкость радио", "$volume%")
            MSlider(
                value = volume / 100f,
                onValueChange = { volume = (it * 100).roundToInt() },
                onValueChangeFinished = { model.ambient.setMusicVolume(volume) },
            )
        }
        MDivider()
        MListRow(
            title = "Гайд по лаунчеру",
            icon = MIcons.info,
            plate = false,
            onClick = {
                model.open(Screen.HOME)
                model.ambient.startTour()
            },
        )
    }
}

/** The desktop's «Живые обои» picker: off, the pixel scene, the four clips. */
@Composable
fun LiveWallpaperSheet(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss, title = "Живые обои") {
        val options = listOf<LiveWallpaper?>(null) + LiveWallpaper.ALL
        options.forEach { option ->
            val id = option?.id ?: LiveWallpaper.OFF
            MListRow(
                title = option?.name ?: "Выкл",
                plate = false,
                onClick = { onPick(id) },
                leading = { LiveWallpaperThumb(option) },
                trailing = {
                    if (id == selected) MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
                },
            )
        }
    }
}

@Composable
private fun LiveWallpaperThumb(option: LiveWallpaper?) {
    val colors = millida
    Box(
        Modifier
            .size(width = 64.dp, height = 44.dp)
            .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame()),
        contentAlignment = Alignment.Center,
    ) {
        when (option?.kind) {
            null -> MIcon(MIcons.image, size = 22.dp)
            LiveWallpaperKind.SCENE -> MIcon(MIcons.grid, size = 22.dp)
            LiveWallpaperKind.VIDEO -> AssetImage(option.poster, Modifier.fillMaxSize().clip(MillidaShape.control), pixelated = false)
        }
    }
}
