package net.millida.launcher.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Prefs
import net.millida.launcher.UiState
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MLocalImage
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.home.AssetImage
import net.millida.launcher.ui.home.LiveWallpaperSheet
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed
import net.millida.launcher.ui.wellFrame
import java.io.File
import java.io.IOException

/** The desktop's still backgrounds (lib/wallpaper.ts STILLS), shipped in the app's assets. */
private class Still(val id: String, val name: String) {
    val asset: String get() = "${Prefs.STILL_FOLDER}/$id.webp"
    val thumb: String get() = "${Prefs.STILL_FOLDER}/$id-thumb.webp"
}

private val STILLS get() = listOf(
    Still("bg5", tr("Золотой час")),
    Still("bg2", tr("Уютная пещера")),
    Still("bg3", tr("Солнечный город")),
    Still("bg4", tr("У камина")),
)

private val TILE_WIDTH = 96.dp
private val TILE_ART_HEIGHT = 56.dp

private fun stillFile(context: Context, still: Still): File = File(File(context.filesDir, Prefs.STILL_FOLDER), "${still.id}.webp")

/**
 * The lobby draws a picture from a file, so a still is unpacked once next to
 * the own pictures, in a folder of its own that removing a picture never touches.
 */
private suspend fun unpackStill(context: Context, still: Still): File = withContext(Dispatchers.IO) {
    val target = stillFile(context, still)
    if (target.length() > 0) return@withContext target
    val folder = target.parentFile ?: throw IOException(tr("Нет места под фон — освободи память и повтори"))
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException(tr("Нет места под фон — освободи память и повтори"))
    val part = File(folder, "${still.id}.part")
    try {
        context.assets.open(still.asset).use { source -> part.outputStream().use { source.copyTo(it) } }
        if (!part.renameTo(target)) throw IOException(tr("Фон не сохранился — освободи память и повтори"))
        target
    } finally {
        if (part.exists()) part.delete()
    }
}

/**
 * Settings → Вид → «Фон» (Settings.tsx): the scene, the four stills, up to four
 * own pictures and a tile to add one; below, the live clips and the random mode.
 */
@Composable
internal fun BackdropSettings(state: UiState, model: LauncherViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val look = rememberLookModel()
    val lookState by look.state.collectAsStateWithLifecycle()
    var pickingLive by rememberSaveable { mutableStateOf(false) }
    val live = LiveWallpaper.of(state.liveWallpaper)
    val picture = state.wallpaperPath.takeIf { live == null }.orEmpty()
    val fail: (Exception) -> Unit = { error -> model.notice(error.message ?: tr("Фон не сохранился — выбери другой"), isError = true) }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val file = importWallpaper(context, uri)
                look.leaveRandom()
                val dropped = look.addToGallery(file.absolutePath)
                model.setWallpaper(file.absolutePath)
                dropped.forEach { discardWallpaper(context, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    SettingHead(tr("Фон"))
    Spacer(Modifier.height(8.dp))
    MButtonRow {
        BackdropTile(tr("Сцена"), selected = live == null && picture.isEmpty(), onClick = {
            look.leaveRandom()
            model.setWallpaper("")
            model.setLiveWallpaper(LiveWallpaper.OFF)
        }) {
            MIcon(MIcons.grid, size = 22.dp)
        }
        STILLS.forEach { still ->
            BackdropTile(still.name, selected = picture.isNotEmpty() && picture == stillFile(context, still).absolutePath, onClick = {
                scope.launch {
                    try {
                        val file = unpackStill(context, still)
                        look.leaveRandom()
                        model.setWallpaper(file.absolutePath)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fail(error)
                    }
                }
            }) {
                AssetImage(still.thumb, Modifier.fillMaxSize(), pixelated = false)
            }
        }
        lookState.gallery.forEach { path ->
            BackdropTile(
                tr("Свой фон"),
                selected = picture == path,
                onClick = {
                    look.leaveRandom()
                    model.setWallpaper(path)
                },
                onRemove = {
                    val rest = look.removeFromGallery(path)
                    if (picture == path) model.setWallpaper(rest.firstOrNull().orEmpty())
                    scope.launch { discardWallpaper(context, path) }
                },
            ) {
                MLocalImage(path, Modifier.fillMaxSize())
            }
        }
        BackdropTile(tr("Свой фон"), selected = false, onClick = { pickPicture.launch("image/*") }) {
            MIcon(MIcons.upload, size = 22.dp)
        }
    }
    Spacer(Modifier.height(8.dp))
    MListRow(
        title = tr("Живые обои"),
        subtitle = live?.name ?: tr("Выкл"),
        icon = MIcons.play,
        plate = false,
        onClick = { pickingLive = true },
    )
    MSwitchRow(
        tr("Случайный фон"),
        tr("Новые живые обои при каждом запуске"),
        lookState.randomWallpaper,
    ) { on -> look.setRandomWallpaper(on, model) }

    if (pickingLive) {
        LiveWallpaperSheet(
            selected = state.liveWallpaper,
            onPick = { id ->
                look.leaveRandom()
                model.setLiveWallpaper(id)
                pickingLive = false
            },
            onDismiss = { pickingLive = false },
        )
    }
}

/** Settings → Вид → «Анимация»: the dancing figure and the live background, both on by default. */
@Composable
internal fun AnimationSettings(state: UiState, model: LauncherViewModel) {
    val look = rememberLookModel()
    val lookState by look.state.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    SettingHead(tr("Анимация"))
    MSwitchRow(
        tr("Персонаж танцует"),
        if (reduced) tr("Выключено в настройках телефона") else null,
        lookState.characterDance,
        look::setCharacterDance,
    )
    MDivider()
    MSwitchRow(
        tr("Живой фон"),
        if (reduced) tr("Выключен в настройках телефона") else null,
        state.wallpaperMotion,
        model::setWallpaperMotion,
    )
}

@Composable
private fun BackdropTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    art: @Composable () -> Unit,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (selected) null else UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed && !selected)
    Column(
        Modifier
            .width(TILE_WIDTH)
            .pressShift(progress)
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .width(TILE_WIDTH)
                .height(TILE_ART_HEIGHT)
                .pixelSurface(
                    colors.inset,
                    MillidaCut.control,
                    if (selected) PixelFrame(outline = colors.accent, outlineWidth = 2.dp) else colors.wellFrame(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(if (selected) 2.dp else 0.dp)
                    .clip(MillidaShape.control),
                contentAlignment = Alignment.Center,
            ) { art() }
            if (onRemove != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(24.dp)
                        .pixelSurface(MillidaPixel.textShadow, MillidaCut.badge)
                        .clickable(onClickLabel = tr("Удалить фон"), role = Role.Button, onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    MIcon(MIcons.trash, size = 14.dp, color = MillidaPixel.white)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MillidaType.small,
            color = if (selected) colors.fg else colors.fgSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
