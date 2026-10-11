package net.millida.launcher.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.rating.versionRangeLabel
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButton
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MButtonVariant
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MDangerButton
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.WIDE_ROOMY_HEIGHT_DP
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame
import net.millida.launcher.ui.screenPadding
import net.millida.launcher.ui.servers.webLink

private const val VERSIONS_SHOWN = 10
private const val BODY_LINES = 6

@Composable
internal fun CatalogDetailSheet(
    detail: CatalogDetail,
    target: Profile?,
    installed: InstalledFile?,
    job: ItemJob?,
    modpack: ModpackJob?,
    onDismiss: () -> Unit,
    onInstall: (CatalogVersion?) -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    MBottomSheet(onDismiss = onDismiss) {
        DetailHead(detail)
        Spacer(Modifier.height(16.dp))
        DetailAction(detail, target, installed, job, modpack, onInstall, onRemove)
        DetailAbout(detail)
        Spacer(Modifier.height(16.dp))
        DetailVersions(detail, target, installed, job, modpack, onInstall, onRetry)
        DetailPageLink(detail, onOpenPage)
    }
}

@Composable
private fun DetailHead(detail: CatalogDetail) {
    val colors = millida
    val item = detail.item
    Row(verticalAlignment = Alignment.CenterVertically) {
        MRemoteImage(url = item.iconUrl, icon = detail.tab.icon, size = 60.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MillidaType.dialogTitle,
                color = colors.fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.author.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "от ${item.author}",
                    style = MillidaType.small,
                    color = colors.fgFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            DetailFacts(detail)
        }
    }
}

@Composable
private fun DetailFacts(detail: CatalogDetail) {
    val colors = millida
    val item = detail.item
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadsFact(item.downloads)
        MBadge(item.source.label, colors.info, colors.infoSoft)
        item.loaderIds.take(2).forEach { loader ->
            MBadge(loaderName(loader), colors.fgMuted, colors.surface2)
        }
    }
}

private fun detailBusy(detail: CatalogDetail, job: ItemJob?, modpack: ModpackJob?): Boolean =
    job != null || (detail.tab.kind == null && modpack != null)

@Composable
private fun DetailAction(
    detail: CatalogDetail,
    target: Profile?,
    installed: InstalledFile?,
    job: ItemJob?,
    modpack: ModpackJob?,
    onInstall: (CatalogVersion?) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = millida
    val item = detail.item
    val kind = detail.tab.kind
    val busy = detailBusy(detail, job, modpack)
    when {
        kind == null && modpack != null && modpack.key == item.key -> ModpackProgress(modpack)
        kind == null -> MPrimaryButton(
            text = "Установить",
            onClick = { onInstall(CatalogModel.modpackPick(detail.versions)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && (detail.loading || detail.versions.isNotEmpty()),
            icon = MIcons.download,
            size = MButtonSize.Large,
        )
        job != null -> InstallProgress(job)
        installed != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            MBadge("Установлено", colors.accentText, colors.accentSoft, icon = MIcons.check)
            Spacer(Modifier.weight(1f))
            MDangerButton(text = "Удалить", onClick = onRemove, icon = MIcons.trash, size = MButtonSize.Medium)
        }
        target == null -> Text("Сначала создай сборку", style = MillidaType.body, color = colors.fgMuted)
        else -> MPrimaryButton(
            text = "Поставить в «${target.name}»",
            onClick = { onInstall(null) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !detail.loading && detail.versions.isNotEmpty(),
            icon = MIcons.download,
            size = MButtonSize.Large,
        )
    }
}

private fun detailGallery(detail: CatalogDetail): List<String> =
    detail.project?.gallery?.takeIf { it.isNotEmpty() } ?: detail.item.gallery

@Composable
private fun DetailAbout(detail: CatalogDetail) {
    val colors = millida
    val item = detail.item
    val gallery = detailGallery(detail)
    if (item.description.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text(item.description, style = MillidaType.body, color = colors.fgMuted)
    }
    if (gallery.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(gallery.take(12)) { picture ->
                MRemoteImage(
                    url = picture,
                    icon = MIcons.image,
                    modifier = Modifier.width(232.dp).height(130.dp),
                    shape = MillidaShape.control,
                )
            }
        }
    }
    DetailBody(detail, Modifier.padding(top = 14.dp))
}

@Composable
private fun DetailBody(detail: CatalogDetail, modifier: Modifier = Modifier) {
    val colors = millida
    val body = remember(detail.project?.body) { plainText(detail.project?.body.orEmpty()) }
    var bodyOpen by remember(detail.item.key) { mutableStateOf(false) }
    if (body.isEmpty()) return
    Column(modifier) {
        Text(
            body,
            style = MillidaType.small,
            color = colors.fgSubtle,
            maxLines = if (bodyOpen) Int.MAX_VALUE else BODY_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        MTextButton(
            text = if (bodyOpen) "Свернуть" else "Читать дальше",
            onClick = { bodyOpen = !bodyOpen },
            color = colors.accentText,
        )
    }
}

@Composable
private fun DetailVersions(
    detail: CatalogDetail,
    target: Profile?,
    installed: InstalledFile?,
    job: ItemJob?,
    modpack: ModpackJob?,
    onInstall: (CatalogVersion?) -> Unit,
    onRetry: () -> Unit,
    titled: Boolean = true,
) {
    val colors = millida
    val kind = detail.tab.kind
    val busy = detailBusy(detail, job, modpack)
    var allVersions by remember(detail.item.key) { mutableStateOf(false) }
    if (titled) {
        MSectionTitle(versionsTitle(detail, target).uppercase())
        Spacer(Modifier.height(8.dp))
    }
    when {
        detail.loading -> {
            MSkeletonRow()
            Spacer(Modifier.height(8.dp))
            MSkeletonRow()
        }
        detail.error != null -> MErrorCard(message = detail.error, onRetry = onRetry)
        detail.versions.isEmpty() -> Text(
            if (kind == null) "Нет версий для телефона — выбери другую сборку" else "Нет версий под эту сборку — выбери другую",
            style = MillidaType.body,
            color = colors.fgMuted,
        )
        else -> {
            val shown = if (allVersions) detail.versions else detail.versions.take(VERSIONS_SHOWN)
            MCard(contentPadding = 4.dp) {
                shown.forEachIndexed { index, version ->
                    if (index > 0) MDivider()
                    VersionRow(
                        version = version,
                        installed = installed != null && installed.versionId.isNotEmpty() &&
                            installed.versionId == version.id,
                        actionLabel = if (kind == null) "Установить" else "Поставить",
                        enabled = !busy && (kind == null || target != null),
                        onInstall = { onInstall(version) },
                    )
                }
            }
            if (!allVersions && detail.versions.size > VERSIONS_SHOWN) {
                MTextButton(
                    text = "Ещё ${detail.versions.size - VERSIONS_SHOWN}",
                    onClick = { allVersions = true },
                    icon = MIcons.chevronDown,
                    color = colors.accentText,
                )
            }
        }
    }
}

@Composable
private fun DetailPageLink(detail: CatalogDetail, onOpenPage: (String) -> Unit) {
    val item = detail.item
    val pageUrl = webLink(detail.project?.pageUrl) ?: webLink(item.pageUrl) ?: return
    Spacer(Modifier.height(10.dp))
    MTextButton(
        text = "Открыть на ${item.source.label}",
        onClick = { onOpenPage(pageUrl) },
        icon = MIcons.externalLink,
    )
}

private enum class ItemPageTab(val title: String) { ABOUT("Описание"), GALLERY("Галерея"), VERSIONS("Версии") }

private val ITEM_ASIDE_WIDTH = 300.dp
private val ITEM_ICON = 96.dp
private val ITEM_ICON_RISE = 40.dp
private val ITEM_BANNER = 180.dp
private val ITEM_BANNER_LOW = 104.dp
private const val GALLERY_COLUMNS = 2

/**
 * The desktop's catalogue item page (`ItemPage.tsx`) inside the catalogue in
 * a wide window: «Назад» with the section's name, the cover across a header
 * card with the icon, the name, the facts and the main action, and below it
 * the description, gallery and versions as tabs beside a column with what
 * the item works with and its page on the site.
 */
@Composable
internal fun CatalogItemPage(
    detail: CatalogDetail,
    target: Profile?,
    installed: InstalledFile?,
    job: ItemJob?,
    modpack: ModpackJob?,
    onBack: () -> Unit,
    onInstall: (CatalogVersion?) -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    val item = detail.item
    val gallery = detailGallery(detail)
    var tab by remember(item.key) { mutableStateOf(ItemPageTab.ABOUT) }
    ItemPageFrame(
        section = detail.tab.label,
        cover = item.cover?.takeIf { it.isNotBlank() } ?: gallery.firstOrNull(),
        iconUrl = item.iconUrl,
        fallbackIcon = detail.tab.icon,
        title = item.title,
        author = item.author,
        onBack = onBack,
        facts = { DetailFacts(detail) },
        action = { DetailAction(detail, target, installed, job, modpack, onInstall, onRemove) },
        aside = {
            ItemAsideCard(versionsTitle(detail, target)) {
                val games = versionRangeLabel(item.gameVersions)
                MButtonRow {
                    if (games != null) MBadge(games, colors.fgMuted, colors.surface2)
                    item.loaderIds.filter { it.lowercase() in SHOWN_LOADERS }.distinct().forEach { loader ->
                        MBadge(loaderName(loader), colors.fgMuted, colors.surface2)
                    }
                }
                DetailPageLink(detail, onOpenPage)
            }
        },
        modifier = modifier,
    ) {
        MSegmented(
            options = ItemPageTab.entries,
            selected = tab,
            onSelect = { tab = it },
            label = { option ->
                when (option) {
                    ItemPageTab.GALLERY -> if (gallery.isEmpty()) option.title else "${option.title} ${gallery.size}"
                    ItemPageTab.VERSIONS -> if (detail.versions.isEmpty()) option.title else "${option.title} ${detail.versions.size}"
                    ItemPageTab.ABOUT -> option.title
                }
            },
        )
        when (tab) {
            ItemPageTab.ABOUT -> MCard {
                if (item.description.isNotBlank()) {
                    Text(item.description, style = MillidaType.body, color = colors.fgMuted)
                }
                when {
                    detail.loading -> {
                        Spacer(Modifier.height(10.dp))
                        MSkeletonRow()
                    }
                    detail.error != null -> {
                        Spacer(Modifier.height(10.dp))
                        MErrorCard(message = detail.error, onRetry = onRetry)
                    }
                    else -> DetailBody(detail, Modifier.padding(top = 10.dp))
                }
            }
            ItemPageTab.GALLERY -> if (gallery.isEmpty()) {
                Text("Картинок нет", style = MillidaType.body, color = colors.fgFaint)
            } else {
                ItemGallery(gallery)
            }
            ItemPageTab.VERSIONS -> Column {
                DetailVersions(detail, target, installed, job, modpack, onInstall, onRetry, titled = false)
            }
        }
    }
}

/**
 * The frame every catalogue item page shares, Modrinth, CurseForge and
 * Millida alike, as the desktop's `ItemPage.tsx` does: «Назад» with the
 * [section], a header card with the [cover] across it, the icon rising out
 * of it, the name, [facts] and the [action] on the right, then the [main]
 * column beside the [aside] one. A low window gets a lower cover so the
 * action stays on the first screen.
 */
@Composable
internal fun ItemPageFrame(
    section: String,
    cover: String?,
    iconUrl: String?,
    fallbackIcon: MillidaIcon,
    title: String,
    author: String,
    onBack: () -> Unit,
    facts: @Composable () -> Unit,
    action: @Composable () -> Unit,
    aside: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    main: @Composable ColumnScope.() -> Unit,
) {
    val colors = millida
    val bannerHeight = if (LocalConfiguration.current.screenHeightDp >= WIDE_ROOMY_HEIGHT_DP) ITEM_BANNER else ITEM_BANNER_LOW
    BackHandler(onBack = onBack)
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = screenPadding, end = screenPadding, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MSecondaryButton("Назад", onClick = onBack, icon = MIcons.chevronLeft, size = MButtonSize.Small)
            Spacer(Modifier.width(12.dp))
            Text(section, style = MillidaType.small.copy(fontWeight = FontWeight.Bold), color = colors.fgSubtle, maxLines = 1)
        }
        Column(Modifier.pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())) {
            if (cover != null) {
                MRemoteImage(
                    url = cover,
                    icon = MIcons.image,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bannerHeight),
                    shape = RectangleShape,
                )
            }
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp, top = if (cover != null) 0.dp else 18.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    Modifier
                        .width(ITEM_ICON)
                        .height(if (cover != null) ITEM_ICON - ITEM_ICON_RISE else ITEM_ICON),
                ) {
                    MRemoteImage(
                        url = iconUrl,
                        icon = fallbackIcon,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .wrapContentHeight(Alignment.Bottom, unbounded = true),
                        size = ITEM_ICON,
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        title,
                        style = MillidaType.screenTitle,
                        color = colors.fg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (author.isNotBlank()) {
                        Text("от $author", style = MillidaType.body, color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    facts()
                }
                Box(
                    Modifier
                        .width(ITEM_ASIDE_WIDTH - 40.dp)
                        .padding(top = 12.dp),
                ) {
                    action()
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = main)
            Column(Modifier.width(ITEM_ASIDE_WIDTH), verticalArrangement = Arrangement.spacedBy(10.dp), content = aside)
        }
    }
}

/** A box of the item page's side column, the desktop's `.ci-box` with its heading. */
@Composable
internal fun ItemAsideCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    MCard {
        Text(title, style = MillidaType.rowTitle, color = millida.fg)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ItemGallery(gallery: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        gallery.chunked(GALLERY_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { picture ->
                    MRemoteImage(
                        url = picture,
                        icon = MIcons.image,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(16f / 9f),
                        shape = MillidaShape.control,
                    )
                }
                repeat(GALLERY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: CatalogVersion,
    installed: Boolean,
    actionLabel: String,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    val colors = millida
    MListRow(
        title = version.versionNumber.ifBlank { version.name }.ifBlank { version.id },
        subtitle = versionLine(version),
        plate = false,
        trailing = {
            if (installed) {
                MBadge("Стоит", colors.accentText, colors.accentSoft, icon = MIcons.check)
            } else {
                MButton(
                    text = actionLabel,
                    onClick = onInstall,
                    variant = MButtonVariant.Secondary,
                    size = MButtonSize.Small,
                    enabled = enabled,
                )
            }
        },
    )
}

@Composable
internal fun InstallProgress(job: ItemJob) {
    val colors = millida
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (job.action == ItemAction.REMOVE) "Удаляем" else "Ставим",
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
            )
            job.progress?.let { share ->
                Text("${(share * 100).toInt()}%", style = MillidaType.pixel, color = colors.accentText)
            }
        }
        Spacer(Modifier.height(8.dp))
        MProgressBar(progress = job.progress)
    }
}

@Composable
internal fun ModpackProgress(job: ModpackJob) {
    val colors = millida
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.stage.ifBlank { "Ставим сборку" },
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            job.progress?.let { share ->
                Text("${(share * 100).toInt()}%", style = MillidaType.pixel, color = colors.accentText)
            }
        }
        Spacer(Modifier.height(8.dp))
        MProgressBar(progress = job.progress)
    }
}

@Composable
internal fun DownloadsFact(downloads: Long) {
    val colors = millida
    Row(verticalAlignment = Alignment.CenterVertically) {
        MIcon(MIcons.download, size = 14.dp, color = colors.fgSubtle)
        Spacer(Modifier.width(4.dp))
        Text(compactCount(downloads), style = MillidaType.pixelSmall, color = colors.fgMuted, maxLines = 1)
    }
}

private fun versionsTitle(detail: CatalogDetail, target: Profile?): String {
    val kind = detail.tab.kind
    return when {
        kind == null -> "Версии для телефона"
        target == null -> "Версии"
        else -> "Версии для ${CatalogModel.buildLabel(kind, target)}"
    }
}

private fun versionLine(version: CatalogVersion): String {
    val games = versionRangeLabel(version.gameVersions)
    val loaders = version.loaders
        .map { it.lowercase() }
        .filter { it in SHOWN_LOADERS }
        .distinct()
        .joinToString("/") { loaderName(it) }
    val channel = when (version.versionType.lowercase()) {
        "beta" -> "бета"
        "alpha" -> "альфа"
        else -> ""
    }
    return listOfNotNull(games, loaders.ifEmpty { null }, channel.ifEmpty { null }).joinToString(" · ")
}

private val SHOWN_LOADERS = setOf("fabric", "quilt", "forge", "neoforge", "iris", "optifine", "canvas")

internal fun loaderName(id: String): String = when (id.lowercase()) {
    "fabric" -> "Fabric"
    "quilt" -> "Quilt"
    "forge" -> "Forge"
    "neoforge" -> "NeoForge"
    "iris" -> "Iris"
    "optifine" -> "OptiFine"
    "canvas" -> "Canvas"
    else -> id.replaceFirstChar { it.uppercase() }
}
