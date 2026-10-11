package net.millida.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.millida.launcher.Prefs
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.SupportState
import net.millida.launcher.UiState
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.SettingsSearch
import net.millida.launcher.core.media.LiveWallpaper
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.update.UpdateVerdict
import net.millida.launcher.ui.settings.AccentPicker
import net.millida.launcher.ui.settings.BugSheet
import net.millida.launcher.ui.settings.CrashCard
import net.millida.launcher.ui.settings.FoldHeader
import net.millida.launcher.ui.settings.ReviewSheet
import net.millida.launcher.ui.settings.SettingHead
import net.millida.launcher.ui.settings.SettingHint
import net.millida.launcher.ui.settings.SettingsSection
import net.millida.launcher.ui.social.FriendNotifySection
import net.millida.launcher.ui.settings.discardWallpaper
import net.millida.launcher.ui.settings.importWallpaper
import net.millida.launcher.ui.settings.rememberTrackedAction
import net.millida.launcher.ui.settings.stepIn
import java.util.Locale
import kotlin.math.roundToInt
import net.millida.launcher.ui.home.LiveWallpaperSheet
import net.millida.launcher.ui.home.LobbySettingsSection

private enum class SettingsSheet { None, Renderer, Review, Bug }

private const val UPDATABLE_PREVIEW = 3

/** Words each section is found by, the desktop's row titles and `keys` (Settings.tsx). */
private val SEARCH_WORDS = mapOf(
    "look" to "Внешний вид Тема Тёмная Светлая Авто Цвет кнопок акцент Фон главной обои картинка Живые обои Анимация",
    "sound" to "Звук и вибрация Звуки Minecraft интерфейса клики тишина уведомления Громкость Вибрация",
    "lobby" to "Лобби Музыка сейчас радио включить выключить Включать при запуске мелодия фоновая " +
        "Громкость радио Гайд по лаунчеру обучение тур подсказки помощь",
    "notify" to "Уведомления друзья сообщения звонки",
    "game" to "Игра Рендерер графика Чувствительность обзора Память игре оперативная",
    "cloud" to "Облако сборок синхронизация ПК телефон",
    "update" to "Обновления Millida Mobile версия",
    "storage" to "Хранилище Кэш Копии модов очистить место",
    "advanced-layer" to "Дополнительно Оконный слой",
    "advanced-runtime" to "Дополнительно Рантайм Java",
    "device" to "Об устройстве Телефон Android Процессор Память Лаунчер",
    "feedback" to "Обратная связь Написать отзыв Сообщить об ошибке поддержка диагностика " +
        "Анонимная статистика телеметрия данные приватность",
)

@Composable
fun SettingsScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val support by model.support.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(SettingsSheet.None) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val shown: (String) -> Boolean = { key -> SettingsSearch.matches(query, SEARCH_WORDS[key].orEmpty()) }
    val found = SEARCH_WORDS.keys.any { key -> shown(key) && (key != "notify" || state.millidaSignedIn) }

    LaunchedEffect(Unit) { model.measureCache() }

    val part: @Composable LazyItemScope.(String) -> Unit = { key ->
        SettingsPart(key, state, model, support) { next -> sheet = next }
    }

    if (LocalWideLayout.current) {
        WideSettingsPage(
            query = query,
            onQuery = { query = it },
            visible = { key -> shown(key) && (key != "notify" || state.millidaSignedIn) },
            found = found,
            crash = support.crash != null,
            onBack = { model.back() },
            part = part,
            modifier = modifier,
        )
    } else {
        MScreen(modifier = modifier, title = "Настройки", onBack = { model.back() }) {
            item(key = "search") {
                MField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Найти настройку",
                    leadingIcon = MIcons.search,
                    clearable = true,
                )
            }
            if (support.crash != null) item(key = "crash") { part("crash") }
            if (shown("look")) item(key = "look") { part("look") }
            if (shown("sound")) item(key = "sound") { part("sound") }
            if (shown("lobby")) item(key = "lobby") { part("lobby") }
            if (state.millidaSignedIn && shown("notify")) item(key = "notify") { part("notify") }
            if (shown("game")) item(key = "game") { part("game") }
            if (shown("cloud")) item(key = "cloud") { part("cloud") }
            if (shown("update")) item(key = "update") { part("update") }
            if (shown("storage")) item(key = "storage") { part("storage") }
            if (!searching) item(key = "advanced") { FoldHeader("Дополнительно", advanced, { advanced = !advanced }, stepIn()) }
            if (advanced || searching) {
                if (shown("advanced-layer")) item(key = "advanced-layer") { part("advanced-layer") }
                if (shown("advanced-runtime")) item(key = "advanced-runtime") { part("advanced-runtime") }
            }
            if (shown("device")) item(key = "device") { part("device") }
            if (!found) item(key = "nothing") { MEmptyState(MIcons.search, "Ничего не нашлось") }
            if (shown("feedback")) item(key = "feedback") { part("feedback") }
        }
    }

    val close = { sheet = SettingsSheet.None }
    when (sheet) {
        SettingsSheet.Renderer -> RendererSheet(state, model, close)
        SettingsSheet.Review -> ReviewSheet(
            sending = support.sendingReview,
            onSend = { text, stars, _ ->
                model.support.sendReview(text, stars) { if (sheet == SettingsSheet.Review) sheet = SettingsSheet.None }
            },
            onDismiss = close,
        )
        SettingsSheet.Bug -> BugSheet(
            sending = support.sendingBug,
            onSend = { kind, text, attach, _ ->
                model.support.sendBug(kind, text, attach) { if (sheet == SettingsSheet.Bug) sheet = SettingsSheet.None }
            },
            onDismiss = close,
        )
        SettingsSheet.None -> Unit
    }
}

/** One settings block by its [SEARCH_WORDS] key, shared by the phone list and the wide panel. */
@Composable
private fun LazyItemScope.SettingsPart(
    key: String,
    state: UiState,
    model: LauncherViewModel,
    support: SupportState,
    onSheet: (SettingsSheet) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    when (key) {
        "crash" -> support.crash?.let { note ->
            CrashCard(
                note = note,
                onCopy = {
                    clipboard.setText(AnnotatedString(note.text))
                    model.notice("Отчёт скопирован — вставь его в чат поддержки")
                },
                onSend = model.support::sendCrash,
                onDismiss = model.support::dismissCrash,
                modifier = stepIn(),
            )
        }
        "look" -> LookSection(state, model, stepIn())
        "sound" -> SoundSection(state, model, stepIn())
        "lobby" -> LobbySettingsSection(model, stepIn())
        "notify" -> FriendNotifySection(model, stepIn())
        "game" -> GameSection(state, model, { onSheet(SettingsSheet.Renderer) }, stepIn())
        "cloud" -> CloudSection(state, model, stepIn())
        "update" -> UpdateSection(state, model, stepIn())
        "storage" -> StorageSection(state, model, stepIn())
        "advanced-layer" -> WindowLayerSection(state, model, stepIn())
        "advanced-runtime" -> RuntimeSection(state, model, stepIn())
        "device" -> DeviceSection(state, model, stepIn())
        "feedback" -> FeedbackSection(
            support = support,
            model = model,
            onReview = { onSheet(SettingsSheet.Review) },
            onBug = { onSheet(SettingsSheet.Bug) },
            modifier = stepIn(),
        )
    }
}

/**
 * The desktop's settings tabs (Settings.tsx `SECTIONS`) with the blocks this
 * launcher has. The desktop's «Аккаунт» holds nothing the phone keeps, so it
 * has no tab here; the phone's «Дополнительно» blocks sit where the desktop
 * keeps the same things: the window layer and old versions in «Игра», the
 * runtime next to Java in «Файлы».
 */
private enum class SettingsGroup(val label: String, val keys: List<String>) {
    Look("Вид", listOf("look")),
    Sound("Звук", listOf("sound", "lobby", "notify")),
    Game("Игра", listOf("game", "advanced-layer")),
    Files("Файлы", listOf("storage", "advanced-runtime", "cloud")),
    About("Лаунчер", listOf("update", "device", "feedback")),
}

/** `.s2 { max-width: 860px }`, and below an 860px window the desktop narrows its search to 160px. */
private val WIDE_PAGE_MAX_WIDTH = 860.dp
private const val WIDE_NARROW_WINDOW_DP = 860
private val WIDE_SEARCH_WIDTH = 220.dp
private val WIDE_SEARCH_NARROW_WIDTH = 160.dp

/**
 * The desktop settings page (`#s-settings .s2`): the head with the search
 * scrolls away while the tabs stick to the top, as `.s2-tabs` does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WideSettingsPage(
    query: String,
    onQuery: (String) -> Unit,
    visible: (String) -> Boolean,
    found: Boolean,
    crash: Boolean,
    onBack: () -> Unit,
    part: @Composable LazyItemScope.(String) -> Unit,
    modifier: Modifier,
) {
    val colors = millida
    val searching = query.isNotBlank()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifted by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    var group by rememberSaveable { mutableStateOf(SettingsGroup.Look) }
    val searchWidth = if (LocalConfiguration.current.screenWidthDp < WIDE_NARROW_WINDOW_DP) {
        WIDE_SEARCH_NARROW_WIDTH
    } else {
        WIDE_SEARCH_WIDTH
    }
    val pick = { next: SettingsGroup ->
        group = next
        onQuery("")
        scope.launch { listState.scrollToItem(0) }
        Unit
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        WideTopBar(title = null, onBack = onBack, divider = lifted)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val side = max(screenPadding, (maxWidth - WIDE_PAGE_MAX_WIDTH) / 2)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = side, end = side, top = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "head") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Настройки",
                            modifier = Modifier.weight(1f),
                            style = MillidaType.screenTitle,
                            color = colors.fg,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(16.dp))
                        MField(
                            value = query,
                            onValueChange = onQuery,
                            placeholder = "Найти настройку",
                            modifier = Modifier.width(searchWidth),
                            leadingIcon = MIcons.search,
                            clearable = true,
                        )
                    }
                }
                stickyHeader(key = "tabs") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.bg)
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsGroup.entries.forEach { tab ->
                            MChip(
                                text = tab.label,
                                selected = !searching && tab == group,
                                onClick = { pick(tab) },
                            )
                        }
                    }
                }
                if (crash) item(key = "crash") { part("crash") }
                if (searching) {
                    SettingsGroup.entries.forEach { tab ->
                        val keys = tab.keys.filter(visible)
                        if (keys.isNotEmpty()) {
                            item(key = "found:" + tab.name) {
                                MTextButton(tab.label, onClick = { pick(tab) }, trailingIcon = MIcons.chevronRight)
                            }
                            keys.forEach { key -> item(key = key) { part(key) } }
                        }
                    }
                    if (!found) item(key = "nothing") { MEmptyState(MIcons.search, "Ничего не нашлось") }
                } else {
                    group.keys.filter(visible).forEach { key -> item(key = key) { part(key) } }
                }
            }
        }
    }
}

@Composable
private fun LookSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val colors = millida
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val file = importWallpaper(context, uri)
                val previous = model.ui.value.wallpaperPath
                model.setWallpaper(file.absolutePath)
                if (previous != file.absolutePath) discardWallpaper(context, previous)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                model.notice(error.message ?: "Картинка не сохранилась — выбери другую", isError = true)
            }
        }
    }
    val hasWallpaper = state.wallpaperPath.isNotEmpty()
    val live = LiveWallpaper.of(state.liveWallpaper)
    var pickingLive by rememberSaveable { mutableStateOf(false) }

    SettingsSection("ВНЕШНИЙ ВИД", modifier) {
        SettingHead("Тема")
        Spacer(Modifier.height(8.dp))
        MSegmented(
            options = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_DARK, Prefs.THEME_LIGHT),
            selected = state.themeMode,
            onSelect = model::setThemeMode,
            label = { mode ->
                when (mode) {
                    Prefs.THEME_DARK -> "Тёмная"
                    Prefs.THEME_LIGHT -> "Светлая"
                    else -> "Авто"
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        SettingHead("Цвет кнопок")
        Spacer(Modifier.height(8.dp))
        AccentPicker(state.accentId, state.accentCustom, model::setAccentId, model::setCustomAccent)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 64.dp, height = 44.dp)
                    .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame()),
                contentAlignment = Alignment.Center,
            ) {
                if (hasWallpaper) {
                    MLocalImage(state.wallpaperPath, Modifier.fillMaxSize().clip(MillidaShape.control))
                } else {
                    MIcon(MIcons.image, size = 22.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Фон главной", style = MillidaType.rowTitle, color = colors.fg, maxLines = 1)
                Text(
                    when {
                        live != null -> live.name
                        hasWallpaper -> "Своя картинка"
                        else -> "Стандартный"
                    },
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MSecondaryButton(
                "Выбрать",
                onClick = { pickWallpaper.launch("image/*") },
                icon = MIcons.image,
                size = MButtonSize.Small,
            )
            if (hasWallpaper) {
                MTextButton(
                    "Убрать",
                    onClick = {
                        val previous = state.wallpaperPath
                        model.setWallpaper("")
                        scope.launch { discardWallpaper(context, previous) }
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MListRow(
            title = "Живые обои",
            subtitle = live?.name ?: "Выкл",
            icon = MIcons.play,
            plate = false,
            onClick = { pickingLive = true },
        )
        if (live != null) {
            MSwitchRow(
                "Анимация",
                if (LocalReducedMotion.current) "Выключена в настройках телефона" else null,
                state.wallpaperMotion,
                model::setWallpaperMotion,
            )
        }
    }

    if (pickingLive) {
        LiveWallpaperSheet(
            selected = state.liveWallpaper,
            onPick = { id ->
                model.setLiveWallpaper(id)
                pickingLive = false
            },
            onDismiss = { pickingLive = false },
        )
    }
}

@Composable
private fun SoundSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val feedback = LocalUiFeedback.current
    val preview = if (state.soundMode == Prefs.SOUND_ALL) UiSoundEvent.Click else UiSoundEvent.Notify

    SettingsSection("ЗВУК И ВИБРАЦИЯ", modifier) {
        SettingHead("Звуки Minecraft")
        Spacer(Modifier.height(8.dp))
        MSegmented(
            options = listOf(Prefs.SOUND_OFF, Prefs.SOUND_NOTIFY, Prefs.SOUND_ALL),
            selected = state.soundMode,
            onSelect = { mode ->
                model.setSoundMode(mode)
                if (mode == Prefs.SOUND_ALL) feedback.play(UiSoundEvent.Click)
                if (mode == Prefs.SOUND_NOTIFY) feedback.play(UiSoundEvent.Notify)
            },
            label = { mode ->
                when (mode) {
                    Prefs.SOUND_OFF -> "Выкл"
                    Prefs.SOUND_ALL -> "Все"
                    else -> "Важные"
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        SettingHint(
            when (state.soundMode) {
                Prefs.SOUND_OFF -> "Лаунчер молчит"
                Prefs.SOUND_ALL -> "Сообщения, награды и нажатия"
                else -> "Сообщения, награды, запуск игры"
            },
        )
        if (state.soundMode != Prefs.SOUND_OFF) {
            Spacer(Modifier.height(14.dp))
            SettingHead("Громкость", "${state.soundVolume}%")
            Row(verticalAlignment = Alignment.CenterVertically) {
                MSlider(
                    value = state.soundVolume / 100f,
                    onValueChange = { model.setSoundVolume((it * 100).roundToInt()) },
                    modifier = Modifier.weight(1f),
                    onValueChangeFinished = {
                        model.saveSoundVolume()
                        feedback.play(preview)
                    },
                )
                Spacer(Modifier.width(10.dp))
                MIconButton(
                    MIcons.play,
                    onClick = { feedback.play(UiSoundEvent.Achievement) },
                    tint = millida.fg,
                    label = "Проверить звук",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MDivider()
        MSwitchRow("Вибрация", null, state.haptics, model::setHaptics)
    }
}

@Composable
private fun GameSection(state: UiState, model: LauncherViewModel, onPickRenderer: () -> Unit, modifier: Modifier) {
    var look by remember(state.lookSensitivity) { mutableFloatStateOf(state.lookSensitivity) }
    var memory by remember(state.memoryOverrideMb) { mutableIntStateOf(state.memoryOverrideMb) }
    val renderer = state.renderers.firstOrNull { it.id == state.rendererId }

    SettingsSection("ИГРА", modifier) {
        MListRow(
            title = "Рендерер",
            subtitle = when {
                state.renderers.isEmpty() -> "Появится после установки рантайма"
                renderer != null -> renderer.label.ifEmpty { renderer.id }
                else -> "Выбери"
            },
            icon = MIcons.monitor,
            plate = false,
            onClick = if (state.renderers.isEmpty()) null else onPickRenderer,
        )
        MDivider()
        MSwitchRow(
            "Безопасная графика",
            "Лёгкие настройки — меньше артефактов",
            state.safeGraphics,
            model::setSafeGraphics,
        )
        MDivider()
        FriendsOverlaySwitch()
        MDivider()
        TouchControlsSwitch()
        MDivider()
        Spacer(Modifier.height(10.dp))
        SettingHead("Чувствительность обзора", String.format(Locale.ROOT, "%.1f×", look))
        MSlider(
            value = look,
            onValueChange = { look = it },
            valueRange = 0.3f..3f,
            steps = 26,
            onValueChangeFinished = { model.setSensitivity(look) },
        )
        Spacer(Modifier.height(8.dp))
        SettingHead("Память игре", if (memory > 0) "$memory МБ" else "Авто")
        MSlider(
            value = memory.toFloat(),
            onValueChange = { memory = (it / MemoryTuning.SETTING_STEP_MB).roundToInt() * MemoryTuning.SETTING_STEP_MB },
            valueRange = 0f..state.memoryCeilingMb.toFloat(),
            steps = state.memoryCeilingMb / MemoryTuning.SETTING_STEP_MB - 1,
            onValueChangeFinished = { model.setMemoryOverride(memory) },
        )
        SettingHint("Авто — лаунчер решит сам по свободной памяти")
    }
}

@Composable
private fun RendererSheet(state: UiState, model: LauncherViewModel, onDismiss: () -> Unit) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss, title = "Рендерер") {
        SettingHint("Чёрный экран или артефакты — выбери другой и запусти снова")
        Spacer(Modifier.height(8.dp))
        state.renderers.forEach { option ->
            val on = option.id == state.rendererId
            MListRow(
                title = option.label.ifEmpty { option.id },
                plate = false,
                onClick = {
                    model.setRenderer(option.id)
                    onDismiss()
                },
                trailing = {
                    if (on) MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
                },
            )
        }
    }
}

@Composable
private fun CloudSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    SettingsSection("ОБЛАКО СБОРОК", modifier) {
        if (!state.millidaSignedIn) {
            MListRow(
                title = "Нужен вход Millida",
                subtitle = "Сборки ездят между телефоном и ПК",
                icon = MIcons.lock,
                plate = false,
            )
            Spacer(Modifier.height(8.dp))
            MPrimaryButton("Войти", onClick = model::loginMillida, icon = MIcons.login, size = MButtonSize.Small)
        } else {
            CloudControls(state, model)
        }
    }
}

@Composable
private fun CloudControls(state: UiState, model: LauncherViewModel) {
    val check = rememberTrackedAction()
    val push = rememberTrackedAction()
    val pull = rememberTrackedAction()
    val idle = state.busy == null
    var confirmUpdate by rememberSaveable { mutableStateOf<String?>(null) }
    var allUpdatable by rememberSaveable { mutableStateOf(false) }
    Column {
        SettingHint("Уезжает состав сборок, а не файлы модов")
        state.cloudStatus?.let { cloud ->
            Spacer(Modifier.height(8.dp))
            MInfoRow("На телефоне", cloud.localProfiles.toString(), mono = true)
            MInfoRow("В облаке", cloud.remoteProfiles.toString(), mono = true)
            if (cloud.missingHere.isNotEmpty()) {
                MInfoRow("Нет на телефоне", cloud.missingHere.take(3).joinToString(", "))
            }
        }
        state.cloudReport?.let { report ->
            MInfoRow("Забрано", "новых ${report.installed.size}, обновлено ${report.updated.size}")
            if (report.skipped.isNotEmpty()) MInfoRow("Пропущено", report.skipped.size.toString(), mono = true)
            if (report.failed.isNotEmpty()) {
                MInfoRow("Ошибки", report.failed.size.toString(), valueColor = millida.danger, mono = true)
            }
            val local = state.profiles.map { it.name }.toSet()
            val updatable = report.skipped.map { it.name }.distinct().filter { it in local }
            if (updatable.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SettingHint("Уже есть на телефоне — обнови нужную")
                val shown = if (allUpdatable) updatable else updatable.take(UPDATABLE_PREVIEW)
                shown.forEach { name ->
                    MListRow(
                        title = name,
                        plate = false,
                        trailing = {
                            MTextButton("Обновить", onClick = { confirmUpdate = name }, enabled = idle && !pull.running)
                        },
                    )
                }
                if (updatable.size > UPDATABLE_PREVIEW) {
                    MTextButton(
                        if (allUpdatable) "Свернуть" else "Все: ${updatable.size}",
                        onClick = { allUpdatable = !allUpdatable },
                        icon = if (allUpdatable) MIcons.chevronUp else MIcons.chevronDown,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MSecondaryButton(
                "Проверить",
                onClick = { check.start { model.cloudStatus() } },
                icon = MIcons.refresh,
                size = MButtonSize.Small,
                loading = check.running,
            )
            MSecondaryButton(
                "Выгрузить",
                onClick = { push.start { model.cloudPush() } },
                icon = MIcons.upload,
                size = MButtonSize.Small,
                enabled = idle,
                loading = push.running,
            )
            MPrimaryButton(
                "Забрать",
                onClick = { pull.start { model.cloudPull() } },
                icon = MIcons.download,
                size = MButtonSize.Small,
                enabled = idle,
                loading = pull.running,
            )
        }
    }

    confirmUpdate?.let { name ->
        MConfirmDialog(
            title = "Обновить «$name»?",
            text = "Сборка на телефоне заменится версией из облака",
            confirm = "Обновить",
            onConfirm = {
                confirmUpdate = null
                pull.start { model.cloudPull(listOf(name)) }
            },
            onDismiss = { confirmUpdate = null },
        )
    }
}

@Composable
private fun UpdateSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val colors = millida
    val check = rememberTrackedAction()
    val download = rememberTrackedAction()
    val checkButton: @Composable () -> Unit = {
        MSecondaryButton(
            "Проверить",
            onClick = {
                check.start { model.checkUpdate(manual = true) }
            },
            icon = MIcons.refresh,
            size = MButtonSize.Small,
            loading = check.running,
        )
    }

    SettingsSection("ОБНОВЛЕНИЯ", modifier) {
        SettingHead("Millida Mobile", state.appVersion)
        Spacer(Modifier.height(6.dp))
        when (val verdict = state.updateVerdict) {
            is UpdateVerdict.Available -> {
                SettingHint(
                    "Вышла ${verdict.release.version} · ${humanSize(verdict.release.size)}",
                    color = colors.accentText,
                )
                Spacer(Modifier.height(10.dp))
                if (state.updateProgress > 0f) {
                    MProgressBar(state.updateProgress)
                    Spacer(Modifier.height(6.dp))
                    SettingHint("Качаем: ${(state.updateProgress * 100).roundToInt()}%")
                } else {
                    MPrimaryButton(
                        "Обновить",
                        onClick = { download.start { model.downloadUpdate() } },
                        icon = MIcons.download,
                        loading = download.running,
                    )
                }
            }
            UpdateVerdict.UpToDate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.check, size = 16.dp, color = colors.accentText)
                    Spacer(Modifier.width(8.dp))
                    SettingHint("Стоит последняя версия")
                }
                Spacer(Modifier.height(10.dp))
                checkButton()
            }
            is UpdateVerdict.Rejected -> {
                SettingHint(verdict.reason)
                Spacer(Modifier.height(10.dp))
                checkButton()
            }
            null -> {
                Spacer(Modifier.height(4.dp))
                checkButton()
            }
        }
    }
}

@Composable
private fun StorageSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val clear = rememberTrackedAction()
    val scan = rememberTrackedAction()
    val dedupe = rememberTrackedAction()
    var confirmClear by remember { mutableStateOf(false) }
    val idle = state.busy == null
    val found = state.duplicateGroups > 0

    SettingsSection("ХРАНИЛИЩЕ", modifier) {
        SettingHead("Кэш", humanSize(state.cacheBytes))
        SettingHint("Временные файлы; сборки, миры и снимки не трогаются")
        Spacer(Modifier.height(10.dp))
        if (clear.running) {
            MPixelLoader()
        } else {
            MDangerButton(
                "Очистить",
                onClick = { confirmClear = true },
                icon = MIcons.trash,
                enabled = idle && state.cacheBytes > 0,
            )
        }
        Spacer(Modifier.height(12.dp))
        MDivider()
        Spacer(Modifier.height(12.dp))
        SettingHead("Копии модов", if (found) humanSize(state.duplicateBytes) else null)
        SettingHint(if (found) "Файл останется у каждой сборки, копия — одна" else "Один мод в разных сборках лежит копиями")
        Spacer(Modifier.height(10.dp))
        if (found) {
            MPrimaryButton(
                "Освободить",
                onClick = { dedupe.start { model.runDedupe() } },
                size = MButtonSize.Small,
                enabled = idle,
                loading = dedupe.running,
            )
        } else {
            MSecondaryButton(
                "Найти копии",
                onClick = { scan.start { model.scanDuplicates() } },
                icon = MIcons.search,
                size = MButtonSize.Small,
                enabled = idle,
                loading = scan.running,
            )
        }
    }

    if (confirmClear) {
        MConfirmDialog(
            title = "Очистить кэш?",
            text = "Сборки, миры и снимки останутся",
            confirm = "Очистить",
            onConfirm = {
                confirmClear = false
                clear.start { model.clearCache() }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun WindowLayerSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    SettingsSection("ОКОННЫЙ СЛОЙ", modifier) {
        MSegmented(
            options = listOf(Prefs.LAYER_PACK, Prefs.LAYER_OWN),
            selected = state.windowLayer,
            onSelect = model::setWindowLayer,
            label = { layer -> if (layer == Prefs.LAYER_OWN) "Свой" else "Готовый" },
        )
        Spacer(Modifier.height(6.dp))
        SettingHint("Смени, если игра не открывается или не видит нажатий")
    }
}

@Composable
private fun RuntimeSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val importing = rememberTrackedAction()
    val unpack = rememberTrackedAction()
    val drop = rememberTrackedAction()
    var confirmDrop by remember { mutableStateOf(false) }
    val idle = state.busy == null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importing.start { model.importRuntime { context.contentResolver.openInputStream(uri) } }
    }

    SettingsSection("РАНТАЙМ", modifier) {
        MInfoRow("Установлен", state.importedRuntime ?: "нет")
        if (!state.bundledRuntime && state.importedRuntime == null) {
            SettingHint("Скачается сам при первом запуске игры")
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MPrimaryButton(
                if (state.importedRuntime == null) "Выбрать файл" else "Заменить",
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                icon = MIcons.folder,
                size = MButtonSize.Small,
                enabled = idle,
                loading = importing.running,
            )
            if (state.bundledRuntime) {
                MSecondaryButton(
                    "Развернуть",
                    onClick = { unpack.start { model.installBundledRuntime(force = true) } },
                    icon = MIcons.refresh,
                    size = MButtonSize.Small,
                    enabled = idle,
                    loading = unpack.running,
                )
            }
            if (state.importedRuntime != null) {
                MDangerButton("Удалить", onClick = { confirmDrop = true }, enabled = idle && !drop.running)
            }
        }
    }

    if (confirmDrop) {
        MConfirmDialog(
            title = "Удалить рантайм?",
            text = "Без него игра не запустится, пока не поставишь снова",
            confirm = "Удалить",
            onConfirm = {
                confirmDrop = false
                drop.start { model.dropRuntime() }
            },
            onDismiss = { confirmDrop = false },
        )
    }
}

@Composable
private fun DeviceSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    val parts = state.deviceReport.split(" · ")

    SettingsSection("ОБ УСТРОЙСТВЕ", modifier) {
        if (parts.size >= 4) {
            MInfoRow("Телефон", parts[0])
            MInfoRow("Android", parts[1].removePrefix("Android "), mono = true)
            MInfoRow("Процессор", parts[2], mono = true)
            MInfoRow("Память", parts[3], mono = true)
        } else if (state.deviceReport.isNotBlank()) {
            MInfoRow("Телефон", state.deviceReport)
        }
        MInfoRow("Лаунчер", state.appVersion, mono = true)
        Spacer(Modifier.height(6.dp))
        MTextButton(
            "Скопировать",
            onClick = {
                clipboard.setText(AnnotatedString("Millida Mobile ${state.appVersion} · ${state.deviceReport}"))
                model.notice("Скопировано — вставь в чат поддержки")
            },
            icon = MIcons.copy,
        )
    }
}

@Composable
private fun FeedbackSection(
    support: SupportState,
    model: LauncherViewModel,
    onReview: () -> Unit,
    onBug: () -> Unit,
    modifier: Modifier,
) {
    SettingsSection("ОБРАТНАЯ СВЯЗЬ", modifier) {
        MListRow(
            title = "Написать отзыв",
            subtitle = "Оценка и пара слов",
            icon = MIcons.star,
            plate = false,
            onClick = onReview,
        )
        MDivider()
        MListRow(
            title = "Сообщить об ошибке",
            subtitle = "С журналом последней игры",
            icon = MIcons.flag,
            plate = false,
            onClick = onBug,
        )
        MDivider()
        MSwitchRow(
            "Отчёты о вылетах",
            "Без ников, токенов и файлов",
            support.crashReports,
            model.support::setCrashReports,
        )
        MDivider()
        MSwitchRow(
            "Анонимная статистика",
            "Без ников и файлов — только цифры",
            support.anonymousStats,
            model.support::setAnonymousStats,
        )
    }
}
