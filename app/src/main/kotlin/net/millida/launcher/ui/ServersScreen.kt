package net.millida.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.rating.LicenseFilter
import net.millida.launcher.core.rating.RatedServer
import net.millida.launcher.core.rating.RatingSort
import net.millida.launcher.core.rating.ServerArt
import net.millida.launcher.core.rating.ServerFilters
import net.millida.launcher.core.rating.versionRangeLabel
import net.millida.launcher.core.servers.OwnServers
import net.millida.launcher.ui.catalog.plainText
import net.millida.launcher.ui.home.JoinServerSheet
import net.millida.launcher.ui.servers.OnlineCount
import net.millida.launcher.ui.servers.ServerDetailSheet
import net.millida.launcher.ui.servers.ServerTags
import net.millida.launcher.ui.servers.serverKey

private const val SERVER_SEARCH_DELAY_MS = 400L
private const val LOAD_AHEAD = 4
private const val RATING_ADD_URL = "https://millida.net/rating/add"
private const val BANNER_RATIO = 468f / 60f
private val WIDE_SEARCH_WIDTH = 240.dp
private val WIDE_BANNER_WIDTH = 240.dp
private val WIDE_BANNER_HEIGHT = 60.dp
private val WIDE_JOIN_MIN_WIDTH = 96.dp
private val WIDE_SKELETON_HEIGHT = 92.dp
private const val WIDE_BANNER_FROM_DP = 720
private val LOGO_SIZE = 48.dp
private val BLOCK_SIZE = 30.dp

private enum class ServerFilterSheet { CATEGORY, SORT, LICENSE, ONLINE, VERSION }

@Composable
fun ServersScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val lobby by model.lobby.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var opened by remember { mutableStateOf<RatedServer?>(null) }
    var sheet by remember { mutableStateOf<ServerFilterSheet?>(null) }
    var searched by rememberSaveable { mutableStateOf(state.serverQuery) }
    var pagingMore by remember { mutableStateOf(false) }

    val servers = remember(state.servers) { OwnServers.forPhone(state.servers.distinctBy(::serverKey)) }
    val loading = state.isLoading(Area.SERVERS)
    val error = state.errorOf(Area.SERVERS)
    val filtered = state.serverQuery.isNotBlank() || state.serverLicense != LicenseFilter.ANY ||
        state.serverVersion.isNotEmpty() || state.serverSort != RatingSort.RATING ||
        state.serverCategory.isNotEmpty() || state.serverOnline.isNotEmpty()
    val stale = servers.isNotEmpty() && state.serversFor != state.serverFilterKey

    val copy: (RatedServer) -> Unit = { server ->
        val address = server.joinAddress
        if (address.isNotEmpty()) {
            clipboard.setText(AnnotatedString(address))
            model.notice("Адрес скопирован: $address")
        }
    }
    val play: (RatedServer) -> Unit = { server ->
        model.playServer(server.joinAddress, server.name, server.versionMajors, server.slug)
    }
    val reset: () -> Unit = {
        searched = ""
        model.resetServerFilters()
    }

    LaunchedEffect(state.serverQuery) {
        if (state.serverQuery.trim() == searched.trim()) return@LaunchedEffect
        delay(SERVER_SEARCH_DELAY_MS)
        if (state.serverQuery.trim() == searched.trim()) return@LaunchedEffect
        searched = state.serverQuery
        model.loadServers(reset = true)
    }

    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD
        }
    }
    val canLoadMore = servers.isNotEmpty() && !stale && state.servers.size < state.serversTotal && !loading && error == null
    LaunchedEffect(nearEnd, canLoadMore) {
        if (nearEnd && canLoadMore) {
            pagingMore = true
            model.loadServers(reset = false)
        }
    }
    LaunchedEffect(loading) {
        if (!loading) pagingMore = false
    }

    val wide = LocalWideLayout.current
    val skeletonHeight = if (wide) WIDE_SKELETON_HEIGHT else 132.dp
    val search: @Composable (Modifier) -> Unit = { placement ->
        MField(
            value = state.serverQuery,
            onValueChange = model::setServerQuery,
            placeholder = "Поиск",
            modifier = placement,
            leadingIcon = MIcons.search,
            clearable = true,
            imeAction = ImeAction.Search,
            onImeAction = {
                searched = state.serverQuery
                model.loadServers(reset = true)
            },
        )
    }

    MScreen(
        modifier = modifier.imePadding(),
        title = "Серверы",
        subtitle = if (state.serversTotal > 0) "В рейтинге ${groupDigits(state.serversTotal.toLong())}" else null,
        onBack = { model.back() },
        listState = listState,
        actions = {
            if (wide) {
                search(Modifier.width(WIDE_SEARCH_WIDTH))
                MSecondaryButton(
                    "Свой сервер",
                    onClick = { model.openLinkOrNotice(context, RATING_ADD_URL) },
                    icon = MIcons.plus,
                    size = MButtonSize.Small,
                )
            } else {
                MIconButton(
                    icon = MIcons.plus,
                    onClick = { model.openLinkOrNotice(context, RATING_ADD_URL) },
                    label = "Добавить свой сервер",
                )
            }
        },
    ) {
        if (!wide) {
            item(key = "search") { search(Modifier) }
        }
        item(key = "filters") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MChip(
                    text = ServerFilters.categoryLabel(state.serverCategory)?.takeIf { state.serverCategory.isNotEmpty() } ?: "Категория",
                    selected = state.serverCategory.isNotEmpty(),
                    onClick = { sheet = ServerFilterSheet.CATEGORY },
                    icon = MIcons.grid,
                )
                MChip(
                    text = if (state.serverSort != RatingSort.RATING) ServerFilters.sortLabel(state.serverSort) else "Сортировка",
                    selected = state.serverSort != RatingSort.RATING,
                    onClick = { sheet = ServerFilterSheet.SORT },
                    icon = MIcons.filter,
                )
                MChip(
                    text = if (state.serverLicense != LicenseFilter.ANY) ServerFilters.licenseLabel(state.serverLicense) else "Лицензия",
                    selected = state.serverLicense != LicenseFilter.ANY,
                    onClick = { sheet = ServerFilterSheet.LICENSE },
                    icon = MIcons.key,
                )
                MChip(
                    text = ServerFilters.onlineLabel(state.serverOnline)?.takeIf { state.serverOnline.isNotEmpty() } ?: "Онлайн",
                    selected = state.serverOnline.isNotEmpty(),
                    onClick = { sheet = ServerFilterSheet.ONLINE },
                    icon = MIcons.users,
                )
                if (state.serverVersions.isNotEmpty() || state.serverVersion.isNotEmpty()) {
                    MChip(
                        text = state.serverVersion.ifEmpty { "Версия" },
                        selected = state.serverVersion.isNotEmpty(),
                        onClick = { sheet = ServerFilterSheet.VERSION },
                        icon = MIcons.blocks,
                    )
                }
                if (filtered) {
                    MChip(text = "Сбросить", selected = false, onClick = reset, icon = MIcons.close)
                }
            }
        }
        if (stale && error != null && !loading) {
            item(key = "reload-error") {
                MErrorCard(message = error, onRetry = { model.retry(Area.SERVERS) })
            }
        }
        if (loading && !pagingMore && servers.isNotEmpty()) {
            item(key = "reloading") { MProgressBar(progress = null) }
        }
        when {
            servers.isEmpty() && loading -> items(5, key = { "skeleton-$it" }) { MSkeleton(height = skeletonHeight) }
            servers.isEmpty() && error != null -> item(key = "error") {
                MErrorCard(message = error, onRetry = { model.retry(Area.SERVERS) })
            }
            servers.isEmpty() -> item(key = "empty") {
                MEmptyState(
                    icon = if (filtered) MIcons.search else MIcons.server,
                    title = if (filtered) "Ничего не нашли" else "Здесь пока пусто",
                    actionText = if (filtered) "Сбросить" else null,
                    onAction = if (filtered) reset else null,
                    primary = false,
                )
            }
        }
        items(servers, key = ::serverKey, contentType = { "server" }) { server ->
            ServerCard(
                server = server,
                onCopy = { copy(server) },
                onPlay = { play(server) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(durationMillis = 180, easing = MillidaMotion.steps(3)),
                    placementSpec = null,
                    fadeOutSpec = null,
                ),
                onOpen = { opened = server },
                joining = lobby.joining.isNotEmpty() && lobby.joining == server.joinAddress,
            )
        }
        if (servers.isNotEmpty() && !stale) {
            when {
                loading && pagingMore -> item(key = "more-loading") { MSkeleton(height = skeletonHeight) }
                error != null -> item(key = "more-error") {
                    MErrorCard(
                        message = error,
                        onRetry = {
                            pagingMore = true
                            model.loadServers(reset = false)
                        },
                    )
                }
            }
        }
    }

    when (sheet) {
        ServerFilterSheet.CATEGORY -> MBottomSheet(onDismiss = { sheet = null }, title = "Категория") {
            ServerFilters.CATEGORIES.forEach { (code, label) ->
                FilterOption(
                    text = label,
                    selected = code == state.serverCategory,
                    onClick = {
                        sheet = null
                        model.setServerCategory(code)
                    },
                )
            }
        }
        ServerFilterSheet.SORT -> MBottomSheet(onDismiss = { sheet = null }, title = "Сортировка") {
            ServerFilters.SORTS.forEach { option ->
                FilterOption(
                    text = ServerFilters.sortLabel(option),
                    selected = option == state.serverSort,
                    onClick = {
                        sheet = null
                        model.setServerSort(option)
                    },
                )
            }
        }
        ServerFilterSheet.ONLINE -> MBottomSheet(onDismiss = { sheet = null }, title = "Онлайн") {
            ServerFilters.ONLINE.forEach { (value, label) ->
                FilterOption(
                    text = label,
                    selected = value == state.serverOnline,
                    onClick = {
                        sheet = null
                        model.setServerOnline(value)
                    },
                )
            }
        }
        ServerFilterSheet.LICENSE -> MBottomSheet(onDismiss = { sheet = null }, title = "Лицензия") {
            LicenseFilter.entries.forEach { option ->
                FilterOption(
                    text = ServerFilters.licenseLabel(option),
                    selected = option == state.serverLicense,
                    onClick = {
                        sheet = null
                        model.setServerLicense(option)
                    },
                )
            }
        }
        ServerFilterSheet.VERSION -> MBottomSheet(onDismiss = { sheet = null }, title = "Версия") {
            FilterOption(
                text = "Любая версия",
                selected = state.serverVersion.isEmpty(),
                onClick = {
                    sheet = null
                    model.setServerVersion("")
                },
            )
            state.serverVersions.forEach { facet ->
                FilterOption(
                    text = facet.version,
                    selected = facet.version == state.serverVersion,
                    count = facet.servers,
                    onClick = {
                        sheet = null
                        model.setServerVersion(facet.version)
                    },
                )
            }
        }
        null -> Unit
    }

    opened?.let { server ->
        ServerDetailSheet(
            server = server,
            onDismiss = { opened = null },
            onPlay = {
                opened = null
                play(server)
            },
            onCopy = { copy(server) },
            onOpenLink = { url -> model.openLinkOrNotice(context, url) },
            joining = lobby.joining.isNotEmpty() && lobby.joining == server.joinAddress,
        )
    }

    JoinServerSheet(
        state = state,
        model = model,
        onCreateBuild = { version ->
            model.lobby.requestCreate(version)
            model.open(Screen.HOME)
        },
    )
}

@Composable
private fun FilterOption(text: String, selected: Boolean, onClick: () -> Unit, count: Int? = null) {
    val colors = millida
    MListRow(
        title = text,
        onClick = onClick,
        plate = false,
        trailing = {
            if (count != null) {
                Text(groupDigits(count.toLong()), style = MillidaType.pixelSmall, color = colors.fgFaint)
            }
            if (selected) MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerCard(
    server: RatedServer,
    onCopy: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    joining: Boolean = false,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (onOpen != null) UiSoundEvent.Nav else null)
    val fill by animateColorAsState(
        targetValue = if (pressed && onOpen != null) colors.surface2 else colors.surface,
        animationSpec = tween(80),
        label = "server-fill",
    )
    val address = server.joinAddress
    val launcherOnly = OwnServers.of(server.slug, address) != null
    val about = remember(server.summary, server.description) {
        server.summary.ifEmpty { plainText(server.description).replace('\n', ' ') }
    }
    if (LocalWideLayout.current) {
        ServerRowWide(server, fill, interaction, address, launcherOnly, onCopy, onPlay, onOpen, joining, modifier)
        return
    }
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(fill, MillidaCut.plate, colors.plateFrame())
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClickLabel = "Скопировать адрес",
                onLongClick = if (address.isNotEmpty()) onCopy else null,
                onClick = { onOpen?.invoke() },
            )
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerLogo(server)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    address.ifEmpty { if (launcherOnly) "Вход через лаунчер" else "Вход по заявке" },
                    style = MillidaType.pixelSmall,
                    color = colors.fgFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            OnlineCount(server)
        }
        val banner = server.bannerUrl?.trim().orEmpty()
        var bannerFailed by remember(banner) { mutableStateOf(false) }
        if (banner.isNotEmpty() && !bannerFailed) {
            Spacer(Modifier.height(10.dp))
            AsyncImage(
                model = banner,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(BANNER_RATIO)
                    .clip(MillidaShape.control),
                contentScale = ContentScale.Crop,
                onError = { bannerFailed = true },
            )
        } else if (server.motd.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            MotdPlate(server.motd, maxLines = 2)
        } else if (about.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                about,
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerTags(server)
            }
            if (address.isNotEmpty() || launcherOnly) {
                Spacer(Modifier.width(8.dp))
                MPrimaryButton(
                    text = "Играть",
                    onClick = onPlay,
                    enabled = server.up,
                    icon = MIcons.play,
                    size = MButtonSize.Small,
                    loading = joining,
                )
            }
        }
    }
}

/**
 * A server in landscape is the desktop's `.srv-row`: logo, a fixed banner slot,
 * the name with its pills over the versions and the address, and a right
 * column with the online count above «Играть».
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ServerRowWide(
    server: RatedServer,
    fill: Color,
    interaction: MutableInteractionSource,
    address: String,
    launcherOnly: Boolean,
    onCopy: () -> Unit,
    onPlay: () -> Unit,
    onOpen: (() -> Unit)?,
    joining: Boolean,
    modifier: Modifier,
) {
    val colors = millida
    val roomy = LocalConfiguration.current.screenWidthDp >= WIDE_BANNER_FROM_DP
    val range = versionRangeLabel(server.versionMajors)
    val entry = address.ifEmpty { if (launcherOnly) "Вход через лаунчер" else "" }
    Row(
        modifier
            .fillMaxWidth()
            .pixelSurface(fill, MillidaCut.plate, colors.plateFrame())
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClickLabel = "Скопировать адрес",
                onLongClick = if (address.isNotEmpty()) onCopy else null,
                onClick = { onOpen?.invoke() },
            )
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerLogo(server)
        if (roomy) {
            Spacer(Modifier.width(14.dp))
            ServerBannerSlot(server)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    server.name,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ServerTags(server, withVersions = false)
            }
            if (range != null || entry.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (range != null) ServerFact(MIcons.blocks, range)
                    if (entry.isNotEmpty()) ServerFact(MIcons.link, entry, Modifier.weight(1f, fill = false))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnlineCount(server)
            if (address.isNotEmpty() || launcherOnly) {
                MPrimaryButton(
                    text = "Играть",
                    onClick = onPlay,
                    modifier = Modifier.widthIn(min = WIDE_JOIN_MIN_WIDTH),
                    enabled = server.up,
                    icon = MIcons.play,
                    size = MButtonSize.Small,
                    loading = joining,
                )
            } else {
                MBadge("Вход по заявке", colors.fgMuted, colors.surface2)
            }
        }
    }
}

/**
 * The desktop keeps the banner's place in every row so the names line up: the
 * banner, else the greeting, else an empty slot that only says when the server is down.
 */
@Composable
private fun ServerBannerSlot(server: RatedServer) {
    val colors = millida
    val banner = server.bannerUrl?.trim().orEmpty()
    var bannerFailed by remember(banner) { mutableStateOf(false) }
    val slot = Modifier.size(WIDE_BANNER_WIDTH, WIDE_BANNER_HEIGHT)
    when {
        banner.isNotEmpty() && !bannerFailed -> Box(
            slot.pixelSurface(colors.inset, MillidaCut.control),
        ) {
            AsyncImage(
                model = banner,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MillidaShape.control),
                contentScale = ContentScale.Crop,
                onError = { bannerFailed = true },
            )
        }
        server.motd.isNotBlank() -> MotdPlate(server.motd, modifier = slot, maxLines = 2)
        else -> Box(
            slot
                .pixelSurface(colors.inset, MillidaCut.control)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (!server.up) {
                Text("Сервер не в сети", style = MillidaType.pixelSmall, color = colors.fgSubtle, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ServerFact(icon: MillidaIcon, text: String, modifier: Modifier = Modifier) {
    val colors = millida
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, size = 13.dp, color = colors.fgFaint)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MillidaType.pixelSmall,
            color = colors.fgSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The server's logo; without one, or when it does not load, a Minecraft block on a tint picked by the name. */
@Composable
private fun ServerLogo(server: RatedServer) {
    val key = ServerArt.keyOf(server)
    val logo = server.logoUrl?.trim().orEmpty()
    var logoShown by remember(logo) { mutableStateOf(false) }
    val tints = MillidaPixel.serverTints
    Box(
        Modifier
            .size(LOGO_SIZE)
            .clip(MillidaShape.control)
            .drawBehind { drawRect(tints[ServerArt.tint(key) % tints.size]) },
        contentAlignment = Alignment.Center,
    ) {
        if (!logoShown) {
            AsyncImage(
                model = ServerArt.blockAsset(ServerArt.block(key)),
                contentDescription = null,
                modifier = Modifier.size(BLOCK_SIZE),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
        if (logo.isNotEmpty()) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None,
                onSuccess = { logoShown = true },
                onError = { logoShown = false },
            )
        }
    }
}
