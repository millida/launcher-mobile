package net.millida.launcher.ui

import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import net.millida.launcher.core.media.UiSoundEvent
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val WIDE_MIN_WIDTH_DP = 560

private val WIDE_LIST_CELL = 320.dp

/**
 * From this height a landscape window is a tablet rather than a phone on its
 * side: screens put their titles into the page as the desktop does and
 * covers get their full height. Below it every row of height goes to content.
 */
internal const val WIDE_ROOMY_HEIGHT_DP = 600

/**
 * The one switch between the phone layout and the desktop-like one. Portrait
 * never turns it on, so everything gated on it leaves portrait untouched.
 */
val LocalWideLayout = compositionLocalOf { false }

@Composable
fun ProvideWideLayout(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val wide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenWidthDp >= WIDE_MIN_WIDTH_DP
    CompositionLocalProvider(LocalWideLayout provides wide, content = content)
}

/**
 * Where the desktop strip's «← Лобби» leads from the screen on show: [label]
 * reads «Лобби» when one step back is the lobby and «Назад» otherwise, and
 * [badge] is the friends' counter the desktop pins to that button's corner.
 */
@Immutable
class WideBack(val label: String, val badge: Int, val onBack: () -> Unit)

val LocalWideBack = compositionLocalOf<WideBack?> { null }

/**
 * «Во что играем» on the desktop is one screen with two tabs, the library and
 * the resources; here the library is the lobby's feed and the resources are
 * the catalog screen, so the tab the player is on is kept above both.
 */
@Stable
class WideHub(private val libraryOpen: MutableState<Boolean>) {
    var library: Boolean
        get() = libraryOpen.value
        set(value) {
            libraryOpen.value = value
        }
}

val LocalWideHub = staticCompositionLocalOf { WideHub(mutableStateOf(false)) }

/** How many cells of at least [cell] fit across a screen; one in portrait. */
@Composable
@ReadOnlyComposable
fun adaptiveColumns(cell: Dp = WIDE_LIST_CELL, max: Int = 4): Int {
    if (!LocalWideLayout.current) return 1
    val room = LocalConfiguration.current.screenWidthDp.dp - screenPadding * 2
    return (room / cell).toInt().coerceIn(2, max)
}

/**
 * A list that turns into rows of [columns] cells on a wide window. With one
 * column it is exactly `items(...)`, so the phone list keeps its keys and look.
 */
fun <T> LazyListScope.adaptiveItems(
    list: List<T>,
    columns: Int,
    key: (T) -> Any,
    contentType: Any? = null,
    gap: Dp = 10.dp,
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    if (columns <= 1) {
        items(list, key = key, contentType = { contentType }) { item -> itemContent(item) }
        return
    }
    items(
        list.chunked(columns),
        key = { row -> "row:" + key(row.first()) },
        contentType = { "row:$contentType" },
    ) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            row.forEach { item ->
                Box(Modifier.weight(1f)) { itemContent(item) }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * Tabs down a left rail, the desktop's `.inst-tabs`: the picked one is a
 * green plate, the rest lie flat on the page.
 */
@Composable
fun <T> WideRailTabs(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    icon: (T) -> MillidaIcon?,
    modifier: Modifier = Modifier,
    badge: (T) -> Int = { 0 },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { tab ->
            RailTab(
                text = label(tab),
                icon = icon(tab),
                picked = tab == selected,
                badge = badge(tab),
                onClick = { if (tab != selected) onSelect(tab) },
            )
        }
    }
}

@Composable
private fun RailTab(text: String, icon: MillidaIcon?, picked: Boolean, badge: Int, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (picked) null else UiSoundEvent.Nav)
    val ink = if (picked) colors.onAccent else colors.fgMuted
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RAIL_TAB_HEIGHT)
            .then(
                if (picked) {
                    Modifier.pixelSurface(colors.accent, MillidaCut.control, PixelFrame.Button, dots = true)
                } else {
                    Modifier.pixelSurface(if (pressed) colors.surface2 else Color.Transparent, MillidaCut.control)
                },
            )
            .selectable(picked, interaction, null, role = Role.Tab, onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            MIcon(icon, size = 18.dp, color = ink)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MillidaType.rowTitle.copy(fontWeight = if (picked) FontWeight.Black else FontWeight.Bold),
            color = if (picked) colors.onAccent else colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MCounterBadge(badge)
    }
}

private val RAIL_TAB_HEIGHT = 44.dp

/**
 * The desktop hub's two tabs in the strip: the picked one is the green
 * button, the other a raised grey one that asks to be pressed.
 */
@Composable
fun PlayHubTabs(resources: Boolean, onLibrary: () -> Unit, onResources: () -> Unit) {
    val colors = millida
    Row(
        Modifier
            .pixelSurface(colors.inset, MillidaCut.control)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HubTab("Библиотека", MIcons.chest, picked = !resources, onClick = onLibrary)
        HubTab("Ресурсы", MIcons.book, picked = resources, onClick = onResources)
    }
}

@Composable
private fun HubTab(text: String, icon: MillidaIcon, picked: Boolean, onClick: () -> Unit) {
    if (picked) {
        MPrimaryButton(text, onClick = {}, icon = icon, size = MButtonSize.Small)
    } else {
        MSecondaryButton(text, onClick = onClick, icon = icon, size = MButtonSize.Small)
    }
}
