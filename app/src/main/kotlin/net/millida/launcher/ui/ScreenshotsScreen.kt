package net.millida.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.media.Screenshot
import net.millida.launcher.ui.media.ShotTile
import net.millida.launcher.ui.media.ShotViewer
import net.millida.launcher.ui.media.dayLabel
import net.millida.launcher.ui.media.key
import net.millida.launcher.ui.media.shotsCount
import net.millida.launcher.ui.media.startOfDay

private const val GALLERY_PAGE = 60
private const val ALL_BUILDS = ""
private val THUMB_MIN = 100.dp

@Composable
fun ScreenshotsScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val colors = millida
    val fresh = state.shotsScope == ALL_BUILDS
    val shots = if (fresh) state.shots else emptyList()
    val error = state.errorOf(Area.SHOTS)
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val lifted by remember(grid) { derivedStateOf { grid.canScrollBackward } }
    val names = remember(state.profiles) { state.profiles.associate { it.id to it.name } }
    val caption: (Screenshot) -> String = { names[it.profileId] ?: "" }
    val days = remember(shots) { shots.groupBy { startOfDay(it.takenAt) } }

    LaunchedEffect(Unit) { model.loadShots() }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        MTopBar(
            title = "Снимки",
            onBack = { model.back() },
            subtitle = if (shots.isEmpty()) null else shotsCount(maxOf(state.shotsTotal, shots.size)),
            divider = lifted,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(THUMB_MIN),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = grid,
            contentPadding = PaddingValues(start = screenPadding, end = screenPadding, top = 6.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (error != null) {
                item(key = "shots-error", span = { GridItemSpan(maxLineSpan) }) {
                    MErrorCard(error, onRetry = { model.retry(Area.SHOTS) })
                }
            }
            when {
                shots.isEmpty() && error != null -> Unit
                shots.isEmpty() && (!fresh || state.isLoading(Area.SHOTS)) -> items(12, key = { "shot-skeleton-$it" }) {
                    MSkeleton(height = THUMB_MIN, modifier = Modifier.aspectRatio(1f))
                }
                shots.isEmpty() -> item(key = "shots-empty", span = { GridItemSpan(maxLineSpan) }) {
                    MEmptyState(
                        icon = MIcons.camera,
                        title = "Снимков пока нет",
                        actionText = "Играть",
                        onAction = { model.open(Screen.HOME) },
                        actionIcon = MIcons.play,
                    )
                }
                else -> days.forEach { (day, group) ->
                    item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                        Text(
                            dayLabel(day),
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            style = MillidaType.sectionTitle.copy(fontSize = MillidaType.rowTitle.fontSize),
                            color = colors.fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    items(group, key = { it.key }, contentType = { "shot" }) { shot ->
                        ShotTile(shot, onOpen = { opened = shot.key }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }

    opened?.let { key ->
        ShotViewer(shots = shots, startKey = key, model = model, caption = caption, onDismiss = { opened = null })
    }
}

/**
 * The same gallery serves the build tab and the whole-launcher screen, so a
 * screenshot behaves identically wherever the player found it. It lives inside
 * the build's list, so it lays out plain rows and pages them instead of a
 * second scrolling grid.
 */
@Composable
internal fun ShotsGallery(
    shots: List<Screenshot>,
    model: LauncherViewModel,
    caption: (Screenshot) -> String = { "" },
) {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by remember { mutableIntStateOf(GALLERY_PAGE) }

    if (shots.isEmpty()) {
        MEmptyState(icon = MIcons.camera, title = "Снимков пока нет")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shots.take(shown).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { shot ->
                    ShotTile(shot, onOpen = { opened = shot.key }, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (shots.size > shown) {
            MSecondaryButton(
                "Показать ещё",
                onClick = { shown += GALLERY_PAGE },
                modifier = Modifier.fillMaxWidth(),
                icon = MIcons.chevronDown,
            )
        }
    }

    opened?.let { key ->
        ShotViewer(shots = shots, startKey = key, model = model, caption = caption, onDismiss = { opened = null })
    }
}
