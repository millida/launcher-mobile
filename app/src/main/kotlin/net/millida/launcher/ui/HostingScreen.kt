package net.millida.launcher.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import net.millida.launcher.Area
import net.millida.launcher.HostingModel
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.bedrock.isBedrockAddress
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.hosting.HostingBuyRoad
import net.millida.launcher.ui.hosting.HostingBuySheet
import net.millida.launcher.ui.hosting.HostingRoads
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

private const val LIST_POLL_MS = 20_000L

@Composable
fun HostingScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val hosting = model.hosting
    val panel by hosting.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val signedIn = state.millidaSignedIn
    val error = state.errorOf(Area.HOSTING)
    val hostedCount by rememberUpdatedState(state.hosted.size)
    var road by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(signedIn) {
        if (signedIn) hosting.loadPlans()
    }
    LaunchedEffect(resumed, signedIn) {
        if (!resumed || !signedIn) return@LaunchedEffect
        hosting.resumed()
        if (hostedCount > 0) model.refreshHosted()
        while (true) {
            delay(LIST_POLL_MS)
            model.refreshHosted()
        }
    }

    val wide = LocalWideLayout.current

    MScreen(
        modifier = modifier,
        title = "Хостинг",
        subtitle = if (signedIn && state.hosted.isNotEmpty()) "Серверов: ${state.hosted.size}" else null,
        onBack = { model.back() },
        actions = {
            if (signedIn && state.hosted.isNotEmpty()) {
                if (wide) {
                    MSecondaryButton("Новый сервер", onClick = { road = HostingBuyRoad.Paid.name }, icon = MIcons.plus, size = MButtonSize.Small)
                } else {
                    MIconButton(MIcons.plus, onClick = { road = HostingBuyRoad.Paid.name }, label = "Новый сервер")
                }
            }
            if (signedIn) {
                MIconButton(MIcons.refresh, onClick = { model.refreshHosted() }, label = "Обновить")
            }
        },
    ) {
        if (!signedIn) {
            item(key = "gate") { MSignInGate(onSignIn = { model.loginMillida() }) }
            return@MScreen
        }
        if (error != null) {
            item(key = "error") { MErrorCard(error, onRetry = { model.retry(Area.HOSTING) }) }
        }
        when {
            state.hosted.isNotEmpty() -> items(state.hosted, key = { it.id }) { server ->
                HostedCard(
                    server = server,
                    onCopy = { model.copyHostAddress(clipboard, server.address) },
                    onPlay = { model.playOnAddress(server.address, hostingName(server)) },
                    modifier = Modifier.animateItem(),
                    onOpen = { model.openHostedServer(server.id) },
                    onStart = { hosting.power(server.id, HostingModel.START) },
                    starting = panel.powering.containsKey(server.id),
                )
            }

            state.isLoading(Area.HOSTING) -> if (wide) {
                item(key = "skeleton-0") { MSkeleton(height = WIDE_CARD_SKELETON) }
            } else {
                items(2, key = { "skeleton-$it" }) { MSkeleton(height = 236.dp) }
            }

            error == null -> item(key = "roads") {
                HostingRoads(
                    panel = panel,
                    onPaid = { road = HostingBuyRoad.Paid.name },
                    onFree = { road = HostingBuyRoad.Free.name },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    road?.let { picked ->
        HostingBuySheet(
            model = model,
            panel = panel,
            hosted = state.hosted,
            road = HostingBuyRoad.entries.firstOrNull { it.name == picked },
            onDismiss = { road = null },
        )
    }
}

@Composable
internal fun HostedCard(
    server: HostingServerState,
    onCopy: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onStart: () -> Unit = {},
    starting: Boolean = false,
) {
    val colors = millida
    val status = server.status.uppercase()
    val running = status == "RUNNING"
    val online = server.playersOnline
    val limit = server.maxPlayers.takeIf { it > 0 } ?: server.planMaxPlayers
    val ram = server.planRamMb.takeIf { it > 0 } ?: server.ramMb
    val note = hostingPaidNote(server, "")
    val tag = hostingTag(server)

    val head: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HostingServerBadge(running = running)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hostingName(server),
                    style = MillidaType.rowTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HostingStatusPlate(server.status)
                    if (isBedrockAddress(server.address)) {
                        Spacer(Modifier.width(6.dp))
                        MBadge("Bedrock", colors.warning, colors.warningSoft)
                    } else if (tag.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tag,
                            style = MillidaType.small,
                            color = colors.fgFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (onOpen != null) {
                Spacer(Modifier.width(8.dp))
                MIcon(MIcons.chevronRight, size = 16.dp, color = colors.fgFaint)
            }
        }
    }
    val players: @Composable (Modifier) -> Unit = { placement ->
        HostingFact(
            icon = MIcons.users,
            value = if (limit > 0) "$online / $limit" else "$online",
            caption = "в сети",
            modifier = placement,
            fraction = if (limit > 0) online.toFloat() / limit else null,
        )
    }
    val memory: @Composable (Modifier) -> Unit = { placement ->
        HostingFact(
            icon = MIcons.monitor,
            value = if (ram > 0) "${hostingGb(ram)} ГБ" else "—",
            caption = "памяти",
            modifier = placement,
        )
    }
    val paidNote: @Composable () -> Unit = {
        if (note != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.clock, size = 14.dp, color = colors.fgSubtle)
                Spacer(Modifier.width(6.dp))
                Text(note, style = MillidaType.small, color = colors.fgSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        MButtonRow {
            if (running) {
                MPrimaryButton("Играть", onClick = onPlay, enabled = server.address.isNotEmpty(), icon = MIcons.play)
            } else if (status == "SUSPENDED") {
                MPrimaryButton("Продлить", onClick = onOpen ?: {}, enabled = onOpen != null, icon = MIcons.wallet)
            } else {
                MPrimaryButton(
                    "Запустить",
                    onClick = onStart,
                    enabled = !starting && status !in HOSTING_BUSY_STATES,
                    icon = MIcons.power,
                    loading = starting,
                )
            }
            if (onOpen != null) {
                MSecondaryButton("Управлять", onClick = onOpen, icon = MIcons.settings)
            }
        }
    }

    MCard(modifier = modifier, onClick = onOpen) {
        if (LocalWideLayout.current) {
            Row {
                Column(Modifier.weight(WIDE_SCENE_ACTIONS)) {
                    head()
                    Spacer(Modifier.height(12.dp))
                    HostingAddressWell(server.address, onCopy)
                    paidNote()
                    Spacer(Modifier.height(12.dp))
                    buttons()
                }
                Spacer(Modifier.width(14.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    players(Modifier.fillMaxWidth())
                    memory(Modifier.fillMaxWidth())
                }
            }
        } else {
            head()
            Spacer(Modifier.height(12.dp))
            HostingAddressWell(server.address, onCopy)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                players(Modifier.weight(1f))
                memory(Modifier.weight(1f))
            }
            paidNote()
            Spacer(Modifier.height(12.dp))
            buttons()
        }
    }
}

/** The desktop list loads behind one scene-shaped plate, as tall as a wide card. */
private val WIDE_CARD_SKELETON = 200.dp

/** The desktop server card's two halves, `.hsx-scene` 1.5fr : 1fr - actions left, numbers right. */
private const val WIDE_SCENE_ACTIONS = 1.5f

@Composable
private fun HostingServerBadge(running: Boolean) {
    val colors = millida
    Box(
        Modifier
            .size(48.dp)
            .pixelSurface(if (running) colors.accentSoft else colors.surface2, MillidaCut.control, colors.plateFrame()),
        contentAlignment = Alignment.Center,
    ) {
        MIcon(MIcons.server, size = 26.dp)
    }
}

internal enum class HostingTone { On, Wait, Bad, Off }

internal val HOSTING_BUSY_STATES: Set<String> = setOf("STARTING", "STOPPING", "QUEUED", "INSTALLING", "MIGRATING")

internal fun hostingTone(status: String): HostingTone = when (status.uppercase()) {
    "RUNNING", "ONLINE" -> HostingTone.On
    "STARTING", "STOPPING", "QUEUED", "INSTALLING", "MIGRATING" -> HostingTone.Wait
    "CRASHED", "SUSPENDED" -> HostingTone.Bad
    else -> HostingTone.Off
}

internal fun hostingStatusLabel(status: String): String = when (status.uppercase()) {
    "RUNNING", "ONLINE" -> "Работает"
    "STARTING" -> "Запускается"
    "STOPPING" -> "Останавливается"
    "STOPPED", "OFFLINE" -> "Остановлен"
    "SUSPENDED" -> "Приостановлен"
    "QUEUED" -> "В очереди"
    "SLEEPING" -> "Спит"
    "CRASHED" -> "Упал"
    "INSTALLING" -> "Устанавливается"
    "MIGRATING" -> "Переезжает"
    "ARCHIVED" -> "В архиве"
    else -> "Нет данных"
}

@Composable
internal fun HostingStatusPlate(status: String, modifier: Modifier = Modifier, scene: Color? = null) {
    val colors = millida
    val tone = hostingTone(status)
    val dot = when (tone) {
        HostingTone.On -> colors.accent
        HostingTone.Wait -> colors.warning
        HostingTone.Bad -> colors.danger
        HostingTone.Off -> if (scene != null) MillidaPixel.whiteSoft else colors.fgFaint
    }
    val ink = when {
        scene != null -> MillidaPixel.white
        tone == HostingTone.On -> colors.accentText
        tone == HostingTone.Wait -> colors.warning
        tone == HostingTone.Bad -> colors.danger
        else -> colors.fgSubtle
    }
    val fill = scene ?: when (tone) {
        HostingTone.On -> colors.accentSoft
        HostingTone.Wait -> colors.warningSoft
        HostingTone.Bad -> colors.dangerSoft
        HostingTone.Off -> colors.surface2
    }
    val blink: State<Float>? = if (tone == HostingTone.Wait && !LocalReducedMotion.current) {
        rememberInfiniteTransition(label = "host-status").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000, easing = MillidaMotion.steps(2))),
            label = "host-status-blink",
        )
    } else {
        null
    }
    Row(
        modifier
            .heightIn(min = 24.dp)
            .pixelSurface(fill, MillidaCut.badge)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if ((blink?.value ?: 0f) >= 0.5f) 0.3f else 1f }
                .pixelSurface(dot, 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            hostingStatusLabel(status),
            style = MillidaType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp),
            color = ink,
            maxLines = 1,
        )
    }
}

@Composable
internal fun HostingAddressWell(
    address: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    scene: Color? = null,
) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, if (address.isNotEmpty()) UiSoundEvent.Click else null)
    val base = scene ?: colors.inset
    val fill = if (pressed && address.isNotEmpty()) MillidaPixel.pressShade.compositeOver(base) else base
    val ink = when {
        scene != null && address.isEmpty() -> MillidaPixel.whiteSoft
        scene != null -> MillidaPixel.white
        address.isEmpty() -> colors.fgFaint
        else -> colors.fg
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .pixelSurface(fill, MillidaCut.control, if (scene != null) PixelFrame.None else colors.wellFrame())
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = address.isNotEmpty(),
                onClickLabel = "Скопировать адрес",
                role = Role.Button,
                onClick = onCopy,
            )
            .padding(start = 12.dp, end = 12.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            address.ifEmpty { "Адрес появится после запуска" },
            modifier = Modifier.weight(1f),
            style = if (address.isEmpty()) MillidaType.small else MillidaType.pixel,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (address.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            MIcon(MIcons.copy, size = 18.dp)
        }
    }
}

@Composable
internal fun HostingMeter(fraction: Float, modifier: Modifier = Modifier, empty: Color = millida.inset) {
    val colors = millida
    val share = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val fill = when {
        share >= 0.9f -> colors.danger
        share >= 0.75f -> colors.warning
        else -> colors.accent
    }
    val lit = (share * 10f).roundToInt()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val gap = floor(2.dp.toPx())
        val cell = (size.width - gap * 9f) / 10f
        for (index in 0 until 10) {
            drawRect(
                color = if (index < lit) fill else empty,
                topLeft = Offset(floor(index * (cell + gap)), 0f),
                size = Size(cell, size.height),
            )
        }
    }
}

@Composable
internal fun HostingFact(
    icon: MillidaIcon,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    fill: Color = millida.surface2,
    ink: Color = millida.fg,
    sub: Color = millida.fgSubtle,
    meterEmpty: Color = millida.inset,
) {
    Row(
        modifier
            .heightIn(min = 56.dp)
            .pixelSurface(fill, MillidaCut.control)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(icon, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = MillidaType.rowTitle.copy(fontWeight = FontWeight.ExtraBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caption,
                style = MillidaType.small.copy(fontSize = 11.5.sp, lineHeight = 14.sp),
                color = sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction != null) {
                Spacer(Modifier.height(5.dp))
                HostingMeter(fraction, empty = meterEmpty)
            }
        }
    }
}

internal fun LauncherViewModel.copyHostAddress(clipboard: ClipboardManager, address: String) {
    if (address.isEmpty()) return
    clipboard.setText(AnnotatedString(address))
    notice("Адрес скопирован: $address")
}

internal fun hostingName(server: HostingServerState): String =
    server.name.ifEmpty { server.slug.ifEmpty { "Мой сервер" } }

internal fun hostingTag(server: HostingServerState): String =
    listOf(server.core, server.version).filter { it.isNotEmpty() }.joinToString(" ")

internal fun hostingGb(mb: Int): String =
    if (mb % 1024 == 0) {
        (mb / 1024).toString()
    } else {
        String.format(Locale.ROOT, "%.1f", mb / 1024.0).replace('.', ',')
    }

internal fun hostingRubles(kopecks: Long): String = groupDigits((kopecks + 50) / 100) + " ₽"

internal fun hostingPaidNote(server: HostingServerState, paidUntil: String): String? {
    val paid = hostingDay(paidUntil)
    if (paid.isNotEmpty()) return "Оплачен до $paid"
    val kept = hostingDay(server.worldDeleteAt)
    if (server.free && kept.isNotEmpty()) return "Мир хранится до $kept"
    return null
}

private val RUSSIAN: Locale = Locale.forLanguageTag("ru")
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RUSSIAN)
private val MOMENT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RUSSIAN)
private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RUSSIAN)

private fun hostingTime(raw: String): ZonedDateTime? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val zone = ZoneId.systemDefault()
    return runCatching { Instant.parse(text).atZone(zone) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone) }.getOrNull()
        ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(zone) }.getOrNull()
}

internal fun hostingDay(raw: String): String = hostingTime(raw)?.format(DAY_FORMAT).orEmpty()

internal fun hostingMoment(raw: String): String {
    val time = hostingTime(raw) ?: return ""
    val today = LocalDate.now(time.zone)
    return if (time.toLocalDate() == today) time.format(CLOCK_FORMAT) else time.format(MOMENT_FORMAT)
}
