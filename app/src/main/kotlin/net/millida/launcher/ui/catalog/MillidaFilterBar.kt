package net.millida.launcher.ui.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.catalog.MillidaFacet
import net.millida.launcher.core.catalog.MillidaFacets
import net.millida.launcher.core.catalog.MillidaFilters
import net.millida.launcher.core.catalog.MillidaSort
import net.millida.launcher.core.friends.ruPlural
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.groupDigits
import net.millida.launcher.ui.millida

private enum class FilterSheet { SORT, VERSION, CATEGORY }

internal fun materials(count: Int): String =
    "${groupDigits(count.toLong())} ${ruPlural(count.toLong(), "материал", "материала", "материалов")}"

/** The site's filter column folded into chips: sort, game version and categories, then the result count. */
@Composable
internal fun MillidaFilterBar(
    filters: MillidaFilters,
    facets: MillidaFacets?,
    total: Int?,
    onChange: (MillidaFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    var sheet by remember { mutableStateOf<FilterSheet?>(null) }
    val versions = facets?.versions.orEmpty()
    val categories = facets?.categories.orEmpty()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MChip(
                text = filters.sort?.label ?: "Сортировка",
                selected = filters.sort != null,
                onClick = { sheet = FilterSheet.SORT },
                icon = MIcons.filter,
            )
            if (versions.isNotEmpty() || !filters.version.isNullOrBlank()) {
                MChip(
                    text = filters.version?.takeIf { it.isNotBlank() } ?: "Версия игры",
                    selected = !filters.version.isNullOrBlank(),
                    onClick = { sheet = FilterSheet.VERSION },
                    icon = MIcons.blocks,
                )
            }
            if (categories.isNotEmpty() || !filters.category.isNullOrBlank()) {
                MChip(
                    text = filters.category?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Категории",
                    selected = !filters.category.isNullOrBlank(),
                    onClick = { sheet = FilterSheet.CATEGORY },
                    icon = MIcons.grid,
                )
            }
            if (filters.active) {
                MChip(text = "Сбросить", selected = false, onClick = { onChange(MillidaFilters()) }, icon = MIcons.close)
            }
        }
        if (total != null) {
            Text(materials(total), style = MillidaType.small, color = colors.fgFaint, maxLines = 1)
        }
    }

    val pick: (MillidaFilters) -> Unit = { next ->
        sheet = null
        onChange(next)
    }
    when (sheet) {
        FilterSheet.SORT -> MBottomSheet(onDismiss = { sheet = null }, title = "Сортировка") {
            MillidaSort.entries.forEach { option ->
                SheetOption(option.label, filters.sort == option) {
                    pick(filters.copy(sort = if (filters.sort == option) null else option))
                }
            }
        }
        FilterSheet.VERSION -> MBottomSheet(onDismiss = { sheet = null }, title = "Версия игры") {
            versions.forEach { facet ->
                FacetOption(facet, facet.value, filters.version == facet.value) {
                    pick(filters.copy(version = if (filters.version == facet.value) null else facet.value))
                }
            }
        }
        FilterSheet.CATEGORY -> MBottomSheet(onDismiss = { sheet = null }, title = "Категории") {
            categories.forEach { facet ->
                FacetOption(facet, facet.value.replaceFirstChar { it.uppercase() }, filters.category == facet.value) {
                    pick(filters.copy(category = if (filters.category == facet.value) null else facet.value))
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun SheetOption(text: String, selected: Boolean, onClick: () -> Unit) {
    MListRow(
        title = text,
        onClick = onClick,
        plate = false,
        trailing = { if (selected) MIcon(MIcons.check, size = 18.dp, color = millida.accentText) },
    )
}

@Composable
private fun FacetOption(facet: MillidaFacet, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = millida
    MListRow(
        title = label,
        onClick = onClick,
        plate = false,
        trailing = {
            Text(groupDigits(facet.count.toLong()), style = MillidaType.pixelSmall, color = colors.fgFaint)
            if (selected) MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
        },
    )
}

/** The site's empty section: a search or a filter that found nothing offers the whole section back. */
@Composable
internal fun CatalogEmpty(searching: Boolean, onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    MEmptyState(
        icon = if (searching) MIcons.search else MIcons.inbox,
        title = if (searching) "Ничего не нашлось" else "Здесь пока пусто",
        modifier = modifier,
        actionText = if (searching) "Показать весь раздел" else null,
        onAction = if (searching) onShowAll else null,
        primary = false,
    )
}
