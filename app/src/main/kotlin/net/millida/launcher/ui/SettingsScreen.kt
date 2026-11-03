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
import kotlinx.coroutines.launch
import net.millida.launcher.BuildConfig
import net.millida.launcher.Prefs
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.SupportState
import net.millida.launcher.UiState
import net.millida.launcher.core.MemoryTuning
import net.millida.launcher.core.game.GameResolution
import net.millida.launcher.core.SettingsSearch
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trKey
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.core.update.UpdateVerdict
import net.millida.launcher.core.hud.ControlScheme
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.ui.settings.AccentPicker
import net.millida.launcher.ui.settings.AccountSettingsSection
import net.millida.launcher.ui.settings.BugSheet
import net.millida.launcher.ui.settings.CrashCard
import net.millida.launcher.ui.settings.LanguagePicker
import net.millida.launcher.ui.settings.ReviewSheet
import net.millida.launcher.ui.settings.SettingHead
import net.millida.launcher.ui.settings.SettingHint
import net.millida.launcher.ui.settings.SettingsSection
import net.millida.launcher.ui.settings.AnimationSettings
import net.millida.launcher.ui.settings.AppIconPicker
import net.millida.launcher.ui.settings.BackdropSettings
import net.millida.launcher.ui.settings.ThemeRow
import net.millida.launcher.ui.settings.rememberLookModel
import net.millida.launcher.ui.social.FriendNotifySection
import net.millida.launcher.ui.settings.rememberTrackedAction
import net.millida.launcher.ui.settings.stepIn
import java.util.Locale
import kotlin.math.roundToInt
import net.millida.launcher.ui.home.LobbySettingsSection

private enum class SettingsSheet { None, Renderer, Review, Bug }

private const val UPDATABLE_PREVIEW = 3

/** Words each section is found by, the desktop's row titles and `keys` (Settings.tsx). */
private val SEARCH_WORDS get() = mapOf(
    "look" to tr("Язык интерфейса") + " language " + tr("Внешний вид Тема Тёмная Светлая Авто Цвет кнопок акцент Тема лаунчера PLUS Diamond премиум золото алмаз") + " " +
        tr("Фон главной обои картинка свой фон галерея Живые обои Случайный фон Иконка приложения значок ярлык") + " " +
        tr("Искра Пламя Аметист Легенда Золото Алмаз Анимация Персонаж танцует Живой фон"),
    "sound" to tr("Звук и вибрация Звуки Minecraft интерфейса клики тишина уведомления Громкость Вибрация"),
    "lobby" to tr("Лобби Музыка сейчас радио включить выключить Включать при запуске мелодия фоновая") + " " +
        tr("Громкость радио Гайд по лаунчеру обучение тур подсказки помощь"),
    "notify" to tr("Уведомления друзья сообщения звонки"),
    "game" to tr("Игра Рендерер графика Чувствительность обзора Память игре оперативная"),
    "controls" to tr("Управление Касание Прицел Кнопки Размер кнопок Прозрачность Инверсия автопрыжок вибрация удар Bedrock"),
    "cloud" to tr("Облако сборок синхронизация ПК телефон"),
    "account" to tr("Аккаунт Баланс кошелёк деньги пополнить рубли Видно в профиле приватность Игровая активность") + " " +
        tr("Серверы Часы в игре Список друзей Достижения Маркет Эмоции вдвоём Кто может звать") + " " +
        tr("Статистика использования телеметрия данные"),
    "update" to tr("Обновления Millida Mobile версия"),
    "storage" to tr("Хранилище Кэш Копии модов очистить место"),
    "advanced-layer" to tr("Дополнительно Оконный слой"),
    "advanced-runtime" to tr("Дополнительно Рантайм Java"),
    "device" to tr("Об устройстве Телефон Android Процессор Память Лаунчер"),
    "feedback" to tr("Обратная связь Написать отзыв Сообщить об ошибке поддержка диагностика Отчёты о вылетах"),
    "more" to tr("Ещё Аккаунт Кабинет Награды новости Коды сборок Скриншоты Bedrock Премиум Платные сборки"),
)

/** The launcher's own window layer is a development tool: see Prefs.windowLayer. */
private const val LAYER_SECTION = "advanced-layer"

/** The portrait «Ещё» root tab already holds these entries; only landscape needs them here. */
private const val MORE_SECTION = "more"

@Composable
fun SettingsScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val support by model.support.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(SettingsSheet.None) }
    var query by rememberSaveable { mutableStateOf("") }
    val wide = LocalWideLayout.current
    val shown: (String) -> Boolean = { key ->
        (key != LAYER_SECTION || BuildConfig.TEST_HOOKS) &&
            (key != MORE_SECTION || wide) &&
            SettingsSearch.matches(query, SEARCH_WORDS[key].orEmpty())
    }
    val found = SEARCH_WORDS.keys.any { key -> shown(key) && (key != "notify" || state.millidaSignedIn) }

    LaunchedEffect(Unit) { model.measureCache() }

    val part: @Composable LazyItemScope.(String) -> Unit = { key ->
        SettingsPart(key, state, model, support) { next -> sheet = next }
    }

    SettingsPage(
        query = query,
        onQuery = { query = it },
        visible = { key -> shown(key) && (key != "notify" || state.millidaSignedIn) },
        found = found,
        crash = support.crash != null,
        onBack = { model.back() },
        part = part,
        modifier = modifier,
    )

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
                    model.notice(tr("Отчёт скопирован — вставь его в чат поддержки"))
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
        "account" -> AccountSettingsSection(state, model, stepIn())
        "game" -> GameSection(state, model, { onSheet(SettingsSheet.Renderer) }, stepIn())
        "controls" -> ControlsSection(state, model, stepIn())
        "cloud" -> CloudSection(state, model, stepIn())
        "update" -> UpdateSection(state, model, stepIn())
        "storage" -> StorageSection(state, model, stepIn())
        "advanced-layer" -> WindowLayerSection(state, model, stepIn())
        "advanced-runtime" -> RuntimeSection(state, model, stepIn())
        "device" -> DeviceSection(state, model, stepIn())
        MORE_SECTION -> MoreSectionsSettings(state, model, stepIn())
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
 * The desktop's settings tabs (Settings.tsx `sections`) in its order, on the
 * phone as on a tablet. The desktop's «Клипы» record the game's last minutes,
 * which the phone does not do, so that tab is left out. The window layer sits
 * in «Игра» and the runtime next to Java in «Файлы», where the desktop keeps
 * the same things; «Ещё» holds the entries the lobby menu used to have.
 */
private enum class SettingsGroup(private val labelKey: String, val keys: List<String>) {
    Look(trKey("Вид"), listOf("look")),
    Sound(trKey("Звук"), listOf("sound", "lobby", "notify")),
    Game(trKey("Игра"), listOf("game", "controls", "advanced-layer")),
    Files(trKey("Файлы"), listOf("storage", "advanced-runtime", "cloud")),
    Account(trKey("Аккаунт"), listOf("account")),
    About(trKey("Лаунчер"), listOf("update", "device", "feedback")),
    More(trKey("Ещё"), listOf(MORE_SECTION)),;

    val label: String get() = tr(labelKey)
}

/** `.s2 { max-width: 860px }`, and below an 860px window the desktop narrows its search to 160px. */
private val WIDE_PAGE_MAX_WIDTH = 860.dp
private const val WIDE_NARROW_WINDOW_DP = 860
private val WIDE_SEARCH_WIDTH = 220.dp
private val WIDE_SEARCH_NARROW_WIDTH = 160.dp

/**
 * The desktop settings page (`#s-settings .s2`): the head with the search
 * scrolls away while the tabs stick to the top, as `.s2-tabs` does. A phone
 * held upright gets the same tabs under its own top bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsPage(
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
    val wide = LocalWideLayout.current
    val searching = query.isNotBlank()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifted by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    var picked by rememberSaveable { mutableStateOf(SettingsGroup.Look) }
    val group = if (picked == SettingsGroup.More && !wide) SettingsGroup.Look else picked
    val searchWidth = if (LocalConfiguration.current.screenWidthDp < WIDE_NARROW_WINDOW_DP) {
        WIDE_SEARCH_NARROW_WIDTH
    } else {
        WIDE_SEARCH_WIDTH
    }
    val pick = { next: SettingsGroup ->
        picked = next
        onQuery("")
        scope.launch { listState.scrollToItem(0) }
        Unit
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        if (wide) {
            WideTopBar(title = null, onBack = onBack, divider = lifted)
        } else {
            MTopBar(title = tr("Настройки"), onBack = onBack, divider = lifted)
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val side = if (wide) max(screenPadding, (maxWidth - WIDE_PAGE_MAX_WIDTH) / 2) else screenPadding
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = side, end = side, top = if (wide) 14.dp else 6.dp, bottom = 28.dp + wideBottomInset()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "head") {
                    if (!wide) {
                        MField(
                            value = query,
                            onValueChange = onQuery,
                            placeholder = tr("Найти настройку"),
                            leadingIcon = MIcons.search,
                            clearable = true,
                        )
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("Настройки"),
                            modifier = Modifier.weight(1f),
                            style = MillidaType.screenTitle,
                            color = colors.fg,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(16.dp))
                        MField(
                            value = query,
                            onValueChange = onQuery,
                            placeholder = tr("Найти настройку"),
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
                        SettingsGroup.entries.filter { it != SettingsGroup.More || wide }.forEach { tab ->
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
                    if (!found) item(key = "nothing") { MEmptyState(MIcons.search, tr("Ничего не нашлось")) }
                } else {
                    group.keys.filter(visible).forEach { key -> item(key = key) { part(key) } }
                }
            }
        }
    }
}

@Composable
private fun LookSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val look = rememberLookModel()
    val lookState by look.state.collectAsStateWithLifecycle()

    SettingsSection(tr("ВНЕШНИЙ ВИД"), modifier) {
        SettingHead(tr("Язык интерфейса"))
        Spacer(Modifier.height(8.dp))
        LanguagePicker()
        Spacer(Modifier.height(16.dp))
        SettingHead(tr("Тема"))
        Spacer(Modifier.height(8.dp))
        MSegmented(
            options = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_DARK, Prefs.THEME_LIGHT),
            selected = state.themeMode,
            onSelect = model::setThemeMode,
            label = { mode ->
                when (mode) {
                    Prefs.THEME_DARK -> tr("Тёмная")
                    Prefs.THEME_LIGHT -> tr("Светлая")
                    else -> tr("Авто")
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        SettingHead(tr("Цвет кнопок"))
        Spacer(Modifier.height(8.dp))
        AccentPicker(
            selectedId = state.accentId,
            customHex = state.accentCustom,
            onPick = { id -> look.pickAccent(id, model) },
            onCustom = { hex -> look.pickAccent(hex, model) },
            tier = lookState.access.tier,
            onPremium = { id -> look.pickPremiumAccent(id, model) },
        )
        Spacer(Modifier.height(16.dp))
        SettingHead(tr("Тема лаунчера"))
        Spacer(Modifier.height(8.dp))
        ThemeRow(lookState.theme, lookState.access.tier, onPick = { theme -> look.chooseTheme(theme, model) })
        Spacer(Modifier.height(16.dp))
        BackdropSettings(state, model)
        Spacer(Modifier.height(16.dp))
        SettingHead(tr("Иконка приложения"))
        Spacer(Modifier.height(8.dp))
        AppIconPicker(lookState.icon, lookState.access, onPick = { icon -> look.pickIcon(icon, model) })
        Spacer(Modifier.height(16.dp))
        AnimationSettings(state, model)
    }
}

@Composable
private fun SoundSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    val feedback = LocalUiFeedback.current
    val preview = if (state.soundMode == Prefs.SOUND_ALL) UiSoundEvent.Click else UiSoundEvent.Notify

    SettingsSection(tr("ЗВУК И ВИБРАЦИЯ"), modifier) {
        SettingHead(tr("Звуки Minecraft"))
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
                    Prefs.SOUND_OFF -> tr("Выкл")
                    Prefs.SOUND_ALL -> tr("Все")
                    else -> tr("Важные")
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        SettingHint(
            when (state.soundMode) {
                Prefs.SOUND_OFF -> tr("Лаунчер молчит")
                Prefs.SOUND_ALL -> tr("Сообщения, награды и нажатия")
                else -> tr("Сообщения, награды, запуск игры")
            },
        )
        if (state.soundMode != Prefs.SOUND_OFF) {
            Spacer(Modifier.height(14.dp))
            SettingHead(tr("Громкость"), "${state.soundVolume}%")
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
                    label = tr("Проверить звук"),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MDivider()
        MSwitchRow(tr("Вибрация"), null, state.haptics, model::setHaptics)
    }
}

@Composable
private fun ControlsSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    LaunchedEffect(Unit) { model.loadControls() }
    var look by remember(state.lookSensitivity) { mutableFloatStateOf(state.lookSensitivity) }
    val layout = state.controls
    SettingsSection(tr("УПРАВЛЕНИЕ"), modifier) {
        if (layout == null) {
            MSkeleton(96.dp)
            return@SettingsSection
        }
        var size by remember(layout.shownSizeScale) { mutableFloatStateOf(layout.shownSizeScale) }
        var opacity by remember(layout.shownOpacity) { mutableFloatStateOf(layout.shownOpacity) }
        MSegmented(
            options = ControlScheme.entries.toList(),
            selected = layout.shownScheme,
            onSelect = { scheme -> model.editControls { it.withScheme(scheme) } },
            label = { schemeLabel(it) },
        )
        Spacer(Modifier.height(10.dp))
        SettingHead(tr("Размер"), String.format(Locale.ROOT, "%.1f×", size))
        MSlider(
            value = size,
            onValueChange = { size = it },
            valueRange = HudLayout.MIN_SIZE..HudLayout.MAX_SIZE,
            onValueChangeFinished = { model.editControls { it.withSizeScale(size) } },
        )
        SettingHead(tr("Прозрачность"), "${(opacity * 100).roundToInt()}%")
        MSlider(
            value = opacity,
            onValueChange = { opacity = it },
            valueRange = HudLayout.MIN_OPACITY..1f,
            onValueChangeFinished = { model.editControls { it.withOpacity(opacity) } },
        )
        SettingHead(tr("Чувствительность обзора"), String.format(Locale.ROOT, "%.1f×", look))
        MSlider(
            value = look,
            onValueChange = { look = it },
            valueRange = 0.3f..3f,
            steps = 26,
            onValueChangeFinished = { model.setSensitivity(look) },
        )
        MDivider()
        MSwitchRow(tr("Инверсия Y"), null, layout.invertY) { on -> model.editControls { it.withInvertY(on) } }
        MDivider()
        MSwitchRow(tr("Вибрация"), null, state.haptics, model::setHaptics)
        MDivider()
        MSwitchRow(tr("Автопрыжок"), null, state.autoJump, model::setAutoJump)
    }
}

@Composable
private fun GameSection(state: UiState, model: LauncherViewModel, onPickRenderer: () -> Unit, modifier: Modifier) {
    var look by remember(state.lookSensitivity) { mutableFloatStateOf(state.lookSensitivity) }
    var memory by remember(state.memoryOverrideMb) { mutableIntStateOf(state.memoryOverrideMb) }
    var resolution by remember(state.resolutionPercent) { mutableIntStateOf(state.resolutionPercent) }
    var gamepadOpen by remember { mutableStateOf(false) }
    val renderer = state.renderers.firstOrNull { it.id == state.rendererId }
    if (gamepadOpen) GamepadSheet(model, onDismiss = { gamepadOpen = false })

    SettingsSection(tr("ИГРА"), modifier) {
        MListRow(
            title = tr("Рендерер"),
            subtitle = when {
                state.renderers.isEmpty() -> tr("Появится после установки рантайма")
                state.rendererAuto -> tr("Авто — под телефон и версию игры")
                renderer != null -> renderer.label.ifEmpty { renderer.id }
                else -> tr("Выбери")
            },
            icon = MIcons.monitor,
            plate = false,
            onClick = if (state.renderers.isEmpty()) null else onPickRenderer,
        )
        MDivider()
        MSwitchRow(
            tr("Безопасная графика"),
            tr("Лёгкие настройки — меньше артефактов"),
            state.safeGraphics,
            model::setSafeGraphics,
        )
        MDivider()
        FriendsOverlaySwitch()
        MDivider()
        TouchControlsSwitch()
        MDivider()
        DirectKeyboardSwitch()
        MDivider()
        MListRow(
            title = tr("Геймпад"),
            subtitle = tr("Что делает каждая кнопка"),
            icon = MIcons.menuKey,
            plate = false,
            onClick = { gamepadOpen = true },
        )
        MDivider()
        Spacer(Modifier.height(10.dp))
        SettingHead(tr("Чувствительность обзора"), String.format(Locale.ROOT, "%.1f×", look))

        MSlider(
            value = look,
            onValueChange = { look = it },
            valueRange = 0.3f..3f,
            steps = 26,
            onValueChangeFinished = { model.setSensitivity(look) },
        )
        Spacer(Modifier.height(8.dp))
        SettingHead(tr("Память игре"), if (memory > 0) tr("{memory} МБ", memory) else tr("Авто"))
        MSlider(
            value = memory.toFloat(),
            onValueChange = { memory = (it / MemoryTuning.SETTING_STEP_MB).roundToInt() * MemoryTuning.SETTING_STEP_MB },
            valueRange = 0f..state.memoryCeilingMb.toFloat(),
            steps = state.memoryCeilingMb / MemoryTuning.SETTING_STEP_MB - 1,
            onValueChangeFinished = { model.setMemoryOverride(memory) },
        )
        SettingHint(tr("Авто — лаунчер решит сам по свободной памяти"))
        Spacer(Modifier.height(8.dp))
        SettingHead(tr("Разрешение игры"), "$resolution%")
        MSlider(
            value = resolution.toFloat(),
            onValueChange = { resolution = GameResolution.clamp(it.roundToInt()) },
            valueRange = GameResolution.MIN.toFloat()..GameResolution.FULL.toFloat(),
            steps = (GameResolution.FULL - GameResolution.MIN) / GameResolution.STEP - 1,
            onValueChangeFinished = { model.setResolutionPercent(resolution) },
        )
        SettingHint(tr("Меньше — больше кадров, картинка мягче. Действует со следующего запуска"))
        MDivider()
        MSwitchRow(
            tr("Максимальная частота экрана"),
            tr("Снимает предел 60 кадров на экранах 90–144 Гц; телефон греется сильнее"),
            state.highRefresh,
            model::setHighRefresh,
        )
    }
}

@Composable
private fun RendererSheet(state: UiState, model: LauncherViewModel, onDismiss: () -> Unit) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss, title = tr("Рендерер")) {
        SettingHint(tr("Чёрный экран или артефакты — выбери другой и запусти снова"))
        Spacer(Modifier.height(8.dp))
        MListRow(
            title = tr("Авто"),
            subtitle = tr("Подберём под видеочип и версию игры, после сбоя возьмём другой"),
            plate = false,
            onClick = {
                model.setRenderer("")
                onDismiss()
            },
            trailing = {
                if (state.rendererAuto) MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
            },
        )
        state.renderers.forEach { option ->
            val on = !state.rendererAuto && option.id == state.rendererId
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
    SettingsSection(tr("ОБЛАКО СБОРОК"), modifier) {
        if (!state.millidaSignedIn) {
            MListRow(
                title = tr("Нужен вход Millida"),
                subtitle = tr("Сборки ездят между телефоном и ПК"),
                icon = MIcons.lock,
                plate = false,
            )
            Spacer(Modifier.height(8.dp))
            MPrimaryButton(tr("Войти"), onClick = model::loginMillida, icon = MIcons.login, size = MButtonSize.Small)
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
        SettingHint(tr("Уезжает состав сборок, а не файлы модов"))
        state.cloudStatus?.let { cloud ->
            Spacer(Modifier.height(8.dp))
            MInfoRow(tr("На телефоне"), cloud.localProfiles.toString(), mono = true)
            MInfoRow(tr("В облаке"), cloud.remoteProfiles.toString(), mono = true)
            if (cloud.missingHere.isNotEmpty()) {
                MInfoRow(tr("Нет на телефоне"), cloud.missingHere.take(3).joinToString(", "))
            }
        }
        state.cloudReport?.let { report ->
            MInfoRow(tr("Забрано"), tr("новых {size}, обновлено {size2}", report.installed.size, report.updated.size))
            if (report.skipped.isNotEmpty()) MInfoRow(tr("Пропущено"), report.skipped.size.toString(), mono = true)
            if (report.failed.isNotEmpty()) {
                MInfoRow(tr("Ошибки"), report.failed.size.toString(), valueColor = millida.danger, mono = true)
            }
            val local = state.profiles.map { it.name }.toSet()
            val updatable = report.skipped.map { it.name }.distinct().filter { it in local }
            if (updatable.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SettingHint(tr("Уже есть на телефоне — обнови нужную"))
                val shown = if (allUpdatable) updatable else updatable.take(UPDATABLE_PREVIEW)
                shown.forEach { name ->
                    MListRow(
                        title = name,
                        plate = false,
                        trailing = {
                            MTextButton(tr("Обновить"), onClick = { confirmUpdate = name }, enabled = idle && !pull.running)
                        },
                    )
                }
                if (updatable.size > UPDATABLE_PREVIEW) {
                    MTextButton(
                        if (allUpdatable) tr("Свернуть") else tr("Все: {size}", updatable.size),
                        onClick = { allUpdatable = !allUpdatable },
                        icon = if (allUpdatable) MIcons.chevronUp else MIcons.chevronDown,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MSecondaryButton(
                tr("Проверить"),
                onClick = { check.start { model.cloudStatus() } },
                icon = MIcons.refresh,
                size = MButtonSize.Small,
                loading = check.running,
            )
            MSecondaryButton(
                tr("Выгрузить"),
                onClick = { push.start { model.cloudPush() } },
                icon = MIcons.upload,
                size = MButtonSize.Small,
                enabled = idle,
                loading = push.running,
            )
            MPrimaryButton(
                tr("Забрать"),
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
            title = tr("Обновить «{name}»?", name),
            text = tr("Сборка на телефоне заменится версией из облака"),
            confirm = tr("Обновить"),
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
            tr("Проверить"),
            onClick = {
                check.start { model.checkUpdate(manual = true) }
            },
            icon = MIcons.refresh,
            size = MButtonSize.Small,
            loading = check.running,
        )
    }

    SettingsSection(tr("ОБНОВЛЕНИЯ"), modifier) {
        SettingHead("Millida Mobile", state.appVersion)
        Spacer(Modifier.height(6.dp))
        if (!BuildConfig.SELF_UPDATE) {
            SettingHint(tr("Обновления приходят через RuStore"))
            Spacer(Modifier.height(10.dp))
            MSecondaryButton(
                tr("Открыть в RuStore"),
                onClick = { model.checkUpdate(manual = true) },
                icon = MIcons.externalLink,
                size = MButtonSize.Small,
            )
            if (BuildConfig.PD_CONSENT) {
                val context = LocalContext.current
                Spacer(Modifier.height(10.dp))
                MTextLink(tr("Политика конфиденциальности"), onClick = { model.openLinkOrNotice(context, PRIVACY_URL) })
            }
            return@SettingsSection
        }
        when (val verdict = state.updateVerdict) {
            is UpdateVerdict.Available -> {
                SettingHint(
                    tr("Вышла {version} · {0}", verdict.release.version, humanSize(verdict.release.size)),
                    color = colors.accentText,
                )
                Spacer(Modifier.height(10.dp))
                if (state.updateProgress > 0f) {
                    MProgressBar(state.updateProgress)
                    Spacer(Modifier.height(6.dp))
                    SettingHint(tr("Качаем: {0}%", (state.updateProgress * 100).roundToInt()))
                } else {
                    MPrimaryButton(
                        tr("Обновить"),
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
                    SettingHint(tr("Стоит последняя версия"))
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

    SettingsSection(tr("ХРАНИЛИЩЕ"), modifier) {
        SettingHead(tr("Кэш"), humanSize(state.cacheBytes))
        SettingHint(tr("Временные файлы; сборки, миры и снимки не трогаются"))
        Spacer(Modifier.height(10.dp))
        if (clear.running) {
            MPixelLoader()
        } else {
            MDangerButton(
                tr("Очистить"),
                onClick = { confirmClear = true },
                icon = MIcons.trash,
                enabled = idle && state.cacheBytes > 0,
            )
        }
        Spacer(Modifier.height(12.dp))
        MDivider()
        Spacer(Modifier.height(12.dp))
        SettingHead(tr("Копии модов"), if (found) humanSize(state.duplicateBytes) else null)
        SettingHint(if (found) tr("Файл останется у каждой сборки, копия — одна") else tr("Один мод в разных сборках лежит копиями"))
        Spacer(Modifier.height(10.dp))
        if (found) {
            MPrimaryButton(
                tr("Освободить"),
                onClick = { dedupe.start { model.runDedupe() } },
                size = MButtonSize.Small,
                enabled = idle,
                loading = dedupe.running,
            )
        } else {
            MSecondaryButton(
                tr("Найти копии"),
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
            title = tr("Очистить кэш?"),
            text = tr("Сборки, миры и снимки останутся"),
            confirm = tr("Очистить"),
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
    SettingsSection(tr("ОКОННЫЙ СЛОЙ"), modifier) {
        MSegmented(
            options = listOf(Prefs.LAYER_PACK, Prefs.LAYER_OWN),
            selected = state.windowLayer,
            onSelect = model::setWindowLayer,
            label = { layer -> if (layer == Prefs.LAYER_OWN) tr("Свой") else tr("Готовый") },
        )
        Spacer(Modifier.height(6.dp))
        SettingHint(tr("Смени, если игра не открывается или не видит нажатий"))
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

    SettingsSection(tr("РАНТАЙМ"), modifier) {
        MInfoRow(tr("Установлен"), state.importedRuntime ?: tr("нет"))
        if (!state.bundledRuntime && state.importedRuntime == null) {
            SettingHint(tr("Скачается сам при первом запуске игры"))
        }
        Spacer(Modifier.height(10.dp))
        MButtonRow {
            MPrimaryButton(
                if (state.importedRuntime == null) tr("Выбрать файл") else tr("Заменить"),
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                icon = MIcons.folder,
                size = MButtonSize.Small,
                enabled = idle,
                loading = importing.running,
            )
            if (state.bundledRuntime) {
                MSecondaryButton(
                    tr("Развернуть"),
                    onClick = { unpack.start { model.installBundledRuntime(force = true) } },
                    icon = MIcons.refresh,
                    size = MButtonSize.Small,
                    enabled = idle,
                    loading = unpack.running,
                )
            }
            if (state.importedRuntime != null) {
                MDangerButton(tr("Удалить"), onClick = { confirmDrop = true }, enabled = idle && !drop.running)
            }
        }
    }

    if (confirmDrop) {
        MConfirmDialog(
            title = tr("Удалить рантайм?"),
            text = tr("Без него игра не запустится, пока не поставишь снова"),
            confirm = tr("Удалить"),
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

    SettingsSection(tr("ОБ УСТРОЙСТВЕ"), modifier) {
        if (parts.size >= 4) {
            MInfoRow(tr("Телефон"), parts[0])
            MInfoRow("Android", parts[1].removePrefix("Android "), mono = true)
            MInfoRow(tr("Процессор"), parts[2], mono = true)
            MInfoRow(tr("Память"), parts[3], mono = true)
        } else if (state.deviceReport.isNotBlank()) {
            MInfoRow(tr("Телефон"), state.deviceReport)
        }
        MInfoRow(tr("Лаунчер"), state.appVersion, mono = true)
        Spacer(Modifier.height(6.dp))
        MTextButton(
            tr("Скопировать"),
            onClick = {
                clipboard.setText(AnnotatedString("Millida Mobile ${state.appVersion} · ${state.deviceReport}"))
                model.notice(tr("Скопировано — вставь в чат поддержки"))
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
    SettingsSection(tr("ОБРАТНАЯ СВЯЗЬ"), modifier) {
        MListRow(
            title = tr("Написать отзыв"),
            subtitle = tr("Оценка и пара слов"),
            icon = MIcons.star,
            plate = false,
            onClick = onReview,
        )
        MDivider()
        MListRow(
            title = tr("Сообщить об ошибке"),
            subtitle = tr("С журналом последней игры"),
            icon = MIcons.flag,
            plate = false,
            onClick = onBug,
        )
        MDivider()
        MSwitchRow(
            tr("Отчёты о вылетах"),
            tr("Без ников, токенов и файлов"),
            support.crashReports,
            model.support::setCrashReports,
        )
    }
}
