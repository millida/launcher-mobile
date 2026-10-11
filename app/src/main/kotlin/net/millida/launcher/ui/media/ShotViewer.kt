package net.millida.launcher.ui.media

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.media.Screenshot
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.shareFile
import net.millida.launcher.ui.LocalReducedMotion
import net.millida.launcher.ui.LocalUiFeedback
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MillidaMotion
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.humanSize
import net.millida.launcher.ui.shotMoment
import java.io.File

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOMED = 1.01f

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShotViewer(
    shots: List<Screenshot>,
    startKey: String,
    model: LauncherViewModel,
    caption: (Screenshot) -> String,
    onDismiss: () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    if (shots.isEmpty()) {
        LaunchedEffect(Unit) { dismiss() }
        return
    }
    val context = LocalContext.current
    val feedback = LocalUiFeedback.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val start = remember(startKey) { shots.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { shots.size }
    val zoom = remember { ZoomState() }
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    var chrome by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Screenshot?>(null) }
    var exporting by remember { mutableStateOf<Screenshot?>(null) }
    val page = pager.currentPage.coerceIn(0, shots.lastIndex)
    val current = shots[page]

    LaunchedEffect(Unit) {
        if (!reduced) appear.animateTo(1f, tween(MillidaMotion.base, easing = MillidaMotion.steps(3)))
    }
    LaunchedEffect(page) { zoom.reset() }

    val report: (String?, String) -> Unit = { failure, success ->
        if (failure == null) model.notice(success) else model.notice(failure, isError = true)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val shot = exporting
        exporting = null
        if (uri != null && shot != null) {
            saving = true
            scope.launch {
                val failure = failureOf { copyToDocument(context, model.shotFile(shot.profileId, shot.name), uri) }
                saving = false
                report(failure, "Снимок сохранён")
            }
        }
    }
    val save: (Screenshot) -> Unit = { shot ->
        if (!saving) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saving = true
                scope.launch {
                    val failure = failureOf { saveToGallery(context, model.shotFile(shot.profileId, shot.name), shot.name) }
                    saving = false
                    report(failure, "Снимок в галерее, папка Millida")
                }
            } else {
                exporting = shot
                exportLauncher.launch(shot.name)
            }
        }
    }
    val share: (Screenshot) -> Unit = { shot ->
        val file = try {
            model.shotFile(shot.profileId, shot.name)
        } catch (missing: Exception) {
            null
        }
        if (file == null || !shareFile(context, file, "image/png", "Снимок Millida")) {
            model.notice("Не нашлось приложения, готового принять снимок", isError = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(MillidaPixel.sceneScrim),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoom.zoomed,
                key = { index -> shots.getOrNull(index)?.key ?: index },
            ) { index ->
                val shot = shots.getOrNull(index)
                if (shot != null) {
                    ZoomableShot(
                        shot = shot,
                        zoom = if (index == page) zoom else null,
                        onTap = { chrome = !chrome },
                    )
                }
            }
            AnimatedVisibility(
                visible = chrome,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(MillidaMotion.fast, easing = MillidaMotion.steps(2))),
                exit = fadeOut(tween(MillidaMotion.fast, easing = MillidaMotion.steps(2))),
            ) {
                ViewerTop(
                    shot = current,
                    note = caption(current),
                    position = "${page + 1} / ${shots.size}",
                    onClose = onDismiss,
                )
            }
            AnimatedVisibility(
                visible = chrome,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(MillidaMotion.fast, easing = MillidaMotion.steps(2))),
                exit = fadeOut(tween(MillidaMotion.fast, easing = MillidaMotion.steps(2))),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MillidaPixel.sole)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MPrimaryButton(
                        "Сохранить",
                        onClick = { save(current) },
                        modifier = Modifier.weight(1f),
                        icon = MIcons.download,
                        loading = saving,
                    )
                    MIconButton(MIcons.share, onClick = { share(current) }, label = "Поделиться")
                    MIconButton(MIcons.trash, onClick = { deleting = current }, label = "Удалить")
                }
            }
        }
    }

    deleting?.let { shot ->
        MConfirmDialog(
            title = "Удалить снимок?",
            text = "Он пропадёт с телефона",
            confirm = "Удалить",
            onConfirm = {
                deleting = null
                model.deleteShot(shot.profileId, shot.name) { feedback.play(UiSoundEvent.Delete) }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ViewerTop(shot: Screenshot, note: String, position: String, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MillidaPixel.sole)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIconButton(MIcons.close, onClick = onClose, label = "Закрыть")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                note.ifEmpty { shotMoment(shot.takenAt) },
                style = MillidaType.rowTitle,
                color = MillidaPixel.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(position)
                    if (note.isNotEmpty()) append(" · ").append(shotMoment(shot.takenAt))
                    if (shot.width > 0 && shot.height > 0) append(" · ").append(shot.width).append("×").append(shot.height)
                    append(" · ").append(humanSize(shot.sizeBytes))
                },
                style = MillidaType.pixelSmall,
                color = MillidaPixel.whiteSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ZoomableShot(shot: Screenshot, zoom: ZoomState?, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(zoom) { if (zoom != null) zoomGestures(zoom) }
            .pointerInput(zoom) {
                detectTapGestures(
                    onDoubleTap = { point -> zoom?.toggle(point, size) },
                    onTap = { tap() },
                )
            }
            .graphicsLayer {
                val scale = zoom?.scale ?: 1f
                scaleX = scale
                scaleY = scale
                translationX = zoom?.offset?.x ?: 0f
                translationY = zoom?.offset?.y ?: 0f
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = File(shot.path),
            contentDescription = shot.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

/** One finger pans only when the frame is zoomed; otherwise it belongs to the pager. */
private suspend fun PointerInputScope.zoomGestures(zoom: ZoomState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (fingers > 1 || zoom.zoomed) {
                zoom.transform(event.calculateZoom(), event.calculatePan(), size)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed: Boolean get() = scale > ZOOMED

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun transform(factor: Float, pan: Offset, bounds: IntSize) {
        val next = (scale * factor).coerceIn(1f, MAX_ZOOM)
        scale = next
        offset = if (next > ZOOMED) clamp(offset + pan, next, bounds) else Offset.Zero
    }

    fun toggle(point: Offset, bounds: IntSize) {
        if (zoomed) {
            reset()
            return
        }
        val center = Offset(bounds.width / 2f, bounds.height / 2f)
        scale = DOUBLE_TAP_ZOOM
        offset = clamp((center - point) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM, bounds)
    }

    private fun clamp(value: Offset, scale: Float, bounds: IntSize): Offset {
        val limitX = bounds.width * (scale - 1f) / 2f
        val limitY = bounds.height * (scale - 1f) / 2f
        return Offset(value.x.coerceIn(-limitX, limitX), value.y.coerceIn(-limitY, limitY))
    }
}
