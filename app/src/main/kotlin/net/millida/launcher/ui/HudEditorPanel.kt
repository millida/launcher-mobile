package net.millida.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.hud.ControlScheme
import net.millida.launcher.core.hud.HudAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.hud.HudControl
import net.millida.launcher.core.hud.HudForeign
import net.millida.launcher.core.hud.HudIcons
import net.millida.launcher.core.hud.HudImported
import net.millida.launcher.core.hud.HudSource
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.hud.HudKind
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.core.hud.HudPresets
import net.millida.launcher.core.hud.HudProfiles
import net.millida.launcher.core.hud.HudTransfer
import net.millida.launcher.core.hud.HudVisibility
import java.util.Locale
import kotlin.math.roundToInt

import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trKey
import net.millida.launcher.core.i18n.trText
private enum class EditorTab(private val labelKey: String) {
    CONTROL(trKey("Кнопка")),
    PROFILES(trKey("Раскладки")),
    TOUCH(trKey("Касание")),;

    val label: String get() = tr(labelKey)
}

private enum class PressMode(private val labelKey: String) {
    HOLD(trKey("Удержание")),
    TOGGLE(trKey("Переключатель")),
    LATCH(trKey("Двойной тап")),;

    val label: String get() = tr(labelKey)
}

private enum class KeyPick { REPLACE, ADD }

private val VISIBILITIES = listOf(
    HudVisibility.LOOK,
    HudVisibility.WALKING,
    HudVisibility.FLYING,
    HudVisibility.CURSOR,
    HudVisibility.ALWAYS,
)

private fun visibilityLabel(visibility: HudVisibility): String = when (visibility) {
    HudVisibility.LOOK -> tr("В мире")
    HudVisibility.WALKING -> tr("На земле")
    HudVisibility.FLYING -> tr("В полёте")
    HudVisibility.CURSOR -> tr("В меню")
    HudVisibility.ALWAYS -> tr("Всегда")
}

private const val HOLD_STEP_MS = 25

@Composable
fun HudEditorPanel(
    profiles: HudProfiles,
    selected: HudControl?,
    sensitivity: Float,
    onProfiles: (HudProfiles) -> Unit,
    onPreset: (HudLayout) -> Unit,
    onChange: (HudControl) -> Unit,
    onLayout: (HudLayout) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (HudControl) -> Unit,
    onSensitivity: (Float) -> Unit,
    onNotice: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onSlide: (HudControl) -> Unit = onChange,
    canUndo: Boolean = false,
    onUndo: () -> Unit = {},
    snap: Boolean = true,
    onSnap: (Boolean) -> Unit = {},
    gyro: Boolean? = null,
    onGyro: (Boolean) -> Unit = {},
    acceleration: Boolean = false,
    onAcceleration: (Boolean) -> Unit = {},
) {
    var hidden by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(EditorTab.CONTROL) }
    val colors = millida
    val sheetFrame = colors.plateFrame().copy(outline = colors.dockOutline)
    val layout = profiles.layout

    // The panel stands over the controls, and the one that has to be moved may
    // be exactly underneath. Hiding it leaves the screen to the player.
    if (hidden) {
        Row(
            modifier
                .pixelSurface(colors.raised, MillidaCut.control, sheetFrame)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MSecondaryButton(tr("Показать"), onClick = { hidden = false }, icon = MIcons.chevronUp, size = MButtonSize.Small)
            MSecondaryButton(tr("Отменить"), onClick = onUndo, enabled = canUndo, icon = MIcons.back, size = MButtonSize.Small)
            MPrimaryButton(tr("Готово"), onClick = onDone, icon = MIcons.check, size = MButtonSize.Small)
        }
        return
    }

    val maxHeight = (effectiveScreenHeightDp() * 0.62f).dp
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .pixelSurface(colors.raised, MillidaCut.plate, sheetFrame, corners = PixelCorners.Top)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Управление"),
                modifier = Modifier.weight(1f),
                style = MillidaType.dialogTitle,
                color = colors.fg,
                maxLines = 1,
            )
            MTextButton(tr("Отменить"), onClick = onUndo, enabled = canUndo, icon = MIcons.back)
            MTextButton(tr("Скрыть"), onClick = { hidden = true }, icon = MIcons.chevronDown)
            Spacer(Modifier.width(8.dp))
            MPrimaryButton(tr("Готово"), onClick = onDone, icon = MIcons.check, size = MButtonSize.Small)
        }

        MSegmented(
            options = EditorTab.entries.toList(),
            selected = tab,
            onSelect = { tab = it },
            label = { it.label },
        )

        when (tab) {
            EditorTab.CONTROL -> ControlTab(layout, selected, onChange, onSlide, onRemove, onAdd, snap, onSnap)
            EditorTab.PROFILES -> ProfilesTab(profiles, onProfiles, onPreset, onNotice)
            EditorTab.TOUCH -> TouchTab(layout, sensitivity, onLayout, onSensitivity, gyro, onGyro, acceleration, onAcceleration)
        }
    }
}

@Composable
private fun ControlTab(
    layout: HudLayout,
    selected: HudControl?,
    onChange: (HudControl) -> Unit,
    onSlide: (HudControl) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (HudControl) -> Unit,
    snap: Boolean,
    onSnap: (Boolean) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (selected == null) {
        Text(
            tr("Перетащи кнопку пальцем, коснись её, чтобы настроить"),
            style = MillidaType.small,
            color = millida.fgSubtle,
        )
    } else {
        Text(
            tr("Уголок выбранной кнопки меняет размер"),
            style = MillidaType.small,
            color = millida.fgSubtle,
        )
        SelectedControl(layout, selected, onChange, onSlide, onRemove)
    }

    MSwitchRow(tr("Сетка"), tr("Кнопки встают ровно по линиям"), snap, onSnap)

    MSecondaryButton(
        if (adding) tr("Скрыть список") else tr("Добавить кнопку"),
        onClick = { adding = !adding },
        icon = if (adding) MIcons.chevronUp else MIcons.plus,
        size = MButtonSize.Small,
    )

    if (adding) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            addable.forEach { (label, control) ->
                MChip(label, selected = false, onClick = { onAdd(control()) }, icon = MIcons.plus)
            }
        }
    }
}

@Composable
private fun SelectedControl(
    layout: HudLayout,
    selected: HudControl,
    onChange: (HudControl) -> Unit,
    onSlide: (HudControl) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = millida
    val canReset = (HudPresets.byName(layout.name) ?: HudPresets.bedrock()).controls.any { it.id == selected.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Кнопка «{label}»", selected.label),
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canReset) {
                MTextButton(
                    tr("Сбросить"),
                    onClick = { layout.reset(selected.id).controls.firstOrNull { it.id == selected.id }?.let(onChange) },
                    icon = MIcons.refresh,
                )
                Spacer(Modifier.width(6.dp))
            }
            MDangerButton(tr("Удалить"), onClick = { onRemove(selected.id) }, icon = MIcons.trash)
        }
        EditorRow(tr("Размер")) {
            MSlider(
                value = selected.scale,
                onValueChange = { onSlide(selected.withSize(it)) },
                valueRange = HudControl.MIN_SCALE..HudControl.MAX_SCALE,
            )
        }
        EditorRow(tr("Прозрачность")) {
            MSlider(
                value = selected.shownOpacity(layout.shownOpacity),
                onValueChange = { onSlide(selected.withOpacity(it)) },
                valueRange = HudLayout.MIN_OPACITY..1f,
            )
        }
        if (selected.opacity != null) {
            MTextButton(tr("Как у всех"), onClick = { onChange(selected.withOpacity(null)) }, icon = MIcons.refresh)
        }
        MSegmented(
            options = listOf(false, true),
            selected = selected.hidden,
            onSelect = { onChange(selected.copy(hidden = it)) },
            label = { if (it) tr("Скрыта") else tr("Видна") },
        )
        if (!selected.anchored && selected.kind != HudKind.FLOAT_STICK) {
            MSectionTitle(tr("КОГДА ВИДНА"))
            MSegmented(
                options = VISIBILITIES,
                selected = selected.visibility,
                onSelect = { onChange(selected.copy(visibility = it)) },
                label = ::visibilityLabel,
            )
        }
        if (selected.kind == HudKind.KEY || selected.kind == HudKind.MOUSE) {
            MSegmented(
                options = PressMode.entries.toList(),
                selected = when {
                    selected.toggle -> PressMode.TOGGLE
                    selected.latch -> PressMode.LATCH
                    else -> PressMode.HOLD
                },
                onSelect = { onChange(selected.copy(toggle = it == PressMode.TOGGLE, latch = it == PressMode.LATCH)) },
                label = { it.label },
            )
        }
        if (selected.rebindable) {
            KeyBinding(selected, onChange)
        }
    }
}

@Composable
private fun KeyBinding(selected: HudControl, onChange: (HudControl) -> Unit) {
    var pick by remember(selected.id) { mutableStateOf<KeyPick?>(null) }
    val keys = selected.keys
    MSectionTitle(tr("КЛАВИШИ"))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { code ->
            MChip(
                HudKeys.name(code),
                selected = true,
                onClick = { if (keys.size > 1) onChange(selected.withKeys(keys - code)) },
                icon = if (keys.size > 1) MIcons.close else null,
            )
        }
        MTextButton(
            tr("Сменить"),
            onClick = { pick = if (pick == KeyPick.REPLACE) null else KeyPick.REPLACE },
            icon = MIcons.edit,
        )
        if (keys.size < HudControl.MAX_KEYS) {
            MTextButton(
                tr("Сочетание"),
                onClick = { pick = if (pick == KeyPick.ADD) null else KeyPick.ADD },
                icon = MIcons.plus,
            )
        }
    }
    val mode = pick ?: return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudKeys.bindable.forEach { (code, name) ->
            MChip(
                name,
                selected = code in keys,
                onClick = {
                    onChange(selected.withKeys(if (mode == KeyPick.REPLACE) listOf(code) else keys + code))
                    pick = null
                },
            )
        }
    }
}

@Composable
private fun ProfilesTab(
    profiles: HudProfiles,
    onProfiles: (HudProfiles) -> Unit,
    onPreset: (HudLayout) -> Unit,
    onNotice: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var renaming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val active = profiles.activeName

    fun create(next: HudProfiles) {
        if (next == profiles) onNotice(tr("Раскладок уже {MAX_PROFILES} — удали лишнюю", HudProfiles.MAX_PROFILES)) else onProfiles(next)
    }

    fun commitRename() {
        onProfiles(profiles.rename(active, draft))
        renaming = false
    }

    fun adopt(imported: HudImported) {
        val next = profiles.create(imported.layout.name, imported.layout)
        create(next)
        if (next == profiles) return
        val count = imported.layout.controls.size
        onNotice(
            if (imported.skipped > 0) {
                tr("Раскладка {source}: перенесено кнопок {count}, без пары у нас {skipped}", sourceLabel(imported.source), count, imported.skipped)
            } else {
                tr("Раскладка {source}: перенесено кнопок {count}", sourceLabel(imported.source), count)
            },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readUpTo(HudForeign.MAX_TEXT + 1)
                        if (bytes.size > HudForeign.MAX_TEXT) null else bytes.toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val imported = text?.let(HudForeign::read)
            if (imported == null) {
                onNotice(tr("В файле нет раскладки, которую мы умеем читать — нужна раскладка Millida, FCL, Zalith или PojavLauncher"))
            } else {
                adopt(imported)
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = HudTransfer.encode(profiles.layout)
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            onNotice(if (written) tr("Раскладка сохранена в файл") else tr("Файл не записался — выбери другую папку"))
        }
    }

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profiles.names.forEach { name ->
            MChip(trText(name), selected = name == active, onClick = {
                confirmDelete = false
                renaming = false
                onProfiles(profiles.switchTo(name))
            })
        }
    }

    when {
        renaming -> Row(verticalAlignment = Alignment.CenterVertically) {
            MField(
                value = draft,
                onValueChange = { draft = it.take(HudProfiles.MAX_NAME) },
                placeholder = tr("Название"),
                modifier = Modifier.weight(1f),
                onImeAction = ::commitRename,
            )
            Spacer(Modifier.width(8.dp))
            MPrimaryButton(tr("Сохранить"), onClick = ::commitRename, icon = MIcons.check, size = MButtonSize.Small)
            MTextButton(tr("Отмена"), onClick = { renaming = false })
        }
        confirmDelete -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Удалить «{active}»?", active),
                modifier = Modifier.weight(1f),
                style = MillidaType.body,
                color = millida.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MTextButton(tr("Отмена"), onClick = { confirmDelete = false })
            MDangerButton(tr("Удалить"), onClick = {
                confirmDelete = false
                onProfiles(profiles.delete(active))
            }, icon = MIcons.trash)
        }
        else -> Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MSecondaryButton(tr("Новая"), onClick = { create(profiles.create(HudProfiles.NEW_NAME)) }, icon = MIcons.plus, size = MButtonSize.Small)
            MSecondaryButton(tr("Копия"), onClick = { create(profiles.duplicate()) }, icon = MIcons.copy, size = MButtonSize.Small)
            MSecondaryButton(tr("Переименовать"), onClick = {
                draft = active
                renaming = true
            }, icon = MIcons.edit, size = MButtonSize.Small)
            MDangerButton(tr("Удалить"), onClick = { confirmDelete = true }, enabled = profiles.names.size > 1, icon = MIcons.trash)
        }
    }

    MSectionTitle(tr("ШАБЛОН"))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudPresets.all().forEach { preset ->
            MChip(
                preset.name,
                selected = preset.name == profiles.layout.name,
                onClick = { onPreset(preset) },
            )
        }
    }

    MSectionTitle(tr("ОБМЕН"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MSecondaryButton(
            tr("Скопировать"),
            onClick = {
                clipboard.setText(AnnotatedString(HudTransfer.encode(profiles.layout)))
                onNotice(tr("Раскладка скопирована"))
            },
            icon = MIcons.copy,
            size = MButtonSize.Small,
        )
        MSecondaryButton(
            tr("Вставить"),
            onClick = {
                val imported = clipboard.getText()?.text?.let(HudForeign::read)
                if (imported == null) {
                    onNotice(tr("В буфере нет раскладки — скопируй её кнопкой «Скопировать»"))
                } else {
                    adopt(imported)
                }
            },
            icon = MIcons.download,
            size = MButtonSize.Small,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MSecondaryButton(tr("Из файла"), onClick = { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, icon = MIcons.folder, size = MButtonSize.Small)
        MSecondaryButton(tr("В файл"), onClick = { saveFile.launch(fileName(profiles.activeName)) }, icon = MIcons.upload, size = MButtonSize.Small)
    }
    Text(
        tr("Подходят раскладки Millida, FCL, Zalith и PojavLauncher"),
        style = MillidaType.small,
        color = millida.fgSubtle,
    )
}

private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun fileName(name: String): String =
    name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().ifEmpty { "layout" } + ".json"

private fun sourceLabel(source: HudSource): String = when (source) {
    HudSource.MILLIDA -> "Millida"
    HudSource.POJAV -> "PojavLauncher"
    HudSource.FCL -> "FCL"
    HudSource.ZALITH -> "Zalith"
}

@Composable
private fun TouchTab(
    layout: HudLayout,
    sensitivity: Float,
    onLayout: (HudLayout) -> Unit,
    onSensitivity: (Float) -> Unit,
    gyro: Boolean?,
    onGyro: (Boolean) -> Unit,
    acceleration: Boolean,
    onAcceleration: (Boolean) -> Unit,
) {
    var look by remember(sensitivity) { mutableFloatStateOf(sensitivity) }
    SchemePicker(layout.shownScheme) { onLayout(layout.withScheme(it)) }
    if (gyro != null) {
        MSwitchRow(tr("Обзор гироскопом"), tr("Поворачивай телефон, чтобы смотреть по сторонам"), gyro, onGyro)
    }
    MSwitchRow(tr("Ускорение касаний"), tr("Быстрый взмах поворачивает дальше медленного"), acceleration, onAcceleration)

    EditorRow(tr("Прозрачность")) {
        MSlider(
            value = layout.shownOpacity,
            onValueChange = { onLayout(layout.withOpacity(it)) },
            valueRange = HudLayout.MIN_OPACITY..1f,
        )
    }
    EditorRow(tr("Размер")) {
        MSlider(
            value = layout.shownSizeScale,
            onValueChange = { onLayout(layout.withSizeScale(it)) },
            valueRange = HudLayout.MIN_SIZE..HudLayout.MAX_SIZE,
        )
    }
    MSwitchRow(tr("Инверсия Y"), null, layout.invertY) { onLayout(layout.withInvertY(it)) }
    MSwitchRow(
        tr("Тап бьёт"),
        tr("Короткое касание — удар, а не действие"),
        layout.tapHits,
    ) { hits -> onLayout(layout.withLookTap(if (hits) HudKeys.MOUSE_LEFT else HudKeys.MOUSE_RIGHT)) }
    EditorRow(tr("Обзор {0}", String.format(Locale.ROOT, "%.1f×", look))) {
        MSlider(
            value = look,
            onValueChange = { look = it },
            valueRange = 0.3f..3f,
            steps = 26,
            onValueChangeFinished = { onSensitivity(look) },
        )
    }
    EditorRow(tr("Удержание {shownHoldDelay} мс", layout.shownHoldDelay)) {
        MSlider(
            value = layout.shownHoldDelay.toFloat(),
            onValueChange = { onLayout(layout.withHoldDelay(((it / HOLD_STEP_MS).roundToInt() * HOLD_STEP_MS))) },
            valueRange = HudLayout.MIN_HOLD_MS.toFloat()..HudLayout.MAX_HOLD_MS.toFloat(),
            steps = (HudLayout.MAX_HOLD_MS - HudLayout.MIN_HOLD_MS) / HOLD_STEP_MS - 1,
        )
    }
}

@Composable
internal fun SchemePicker(selected: ControlScheme, onSelect: (ControlScheme) -> Unit) {
    MSegmented(
        options = ControlScheme.entries.toList(),
        selected = selected,
        onSelect = onSelect,
        label = { schemeLabel(it) },
    )
}

internal fun schemeLabel(scheme: ControlScheme): String = when (scheme) {
    ControlScheme.TOUCH -> tr("Касание")
    ControlScheme.CROSSHAIR -> tr("Прицел")
    ControlScheme.BUTTONS -> tr("Кнопки")
}

@Composable
private fun EditorRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(132.dp),
            style = MillidaType.small,
            color = millida.fgSubtle,
            maxLines = 1,
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

private val addable: List<Pair<String, () -> HudControl>> get() = listOf(
    tr("Прыжок") to { key("jump", tr("Прыжок"), HudKeys.SPACE, wide = true) },
    tr("Присесть") to { key("sneak", tr("Присесть"), HudKeys.LEFT_SHIFT, wide = true, toggle = true) },
    tr("Бег") to { key("sprint", tr("Бег"), HudKeys.LEFT_CONTROL, wide = true, toggle = true) },
    "W" to { key("forward", "W", HudKeys.W) },
    "A" to { key("left", "A", HudKeys.A) },
    "S" to { key("back", "S", HudKeys.S) },
    "D" to { key("right", "D", HudKeys.D) },
    tr("Инвентарь") to { key("inventory", tr("Инвентарь"), HudKeys.E, wide = true) },
    tr("Чат") to { key("chat", tr("Чат"), HudKeys.T, wide = true) },
    tr("Бросить") to { key("drop", tr("Бросить"), HudKeys.Q, wide = true) },
    tr("Выбрать") to { key("pick", tr("Выбрать"), HudKeys.F, wide = true) },
    tr("Меню игры") to { key("escape", "Esc", HudKeys.ESCAPE, wide = true, visibility = HudVisibility.ALWAYS) },
    tr("Список") to { key("tab", tr("Игроки"), HudKeys.TAB, wide = true) },
    tr("Отладка") to { key("debug", "F3", HudKeys.F3, wide = true) },
    tr("Интерфейс F1") to { key("hide", "F1", HudKeys.F1) },
    tr("Скриншот F2") to { key("screenshot", "F2", HudKeys.F2) },
    tr("Вид F5") to { key("camera", "F5", HudKeys.F5) },
    tr("Слот 1") to { key("slot1", "1", HudKeys.ONE) },
    tr("Слот 2") to { key("slot2", "2", HudKeys.TWO) },
    tr("Слот 3") to { key("slot3", "3", HudKeys.THREE) },
    tr("Слот 4") to { key("slot4", "4", HudKeys.FOUR) },
    tr("Слот 5") to { key("slot5", "5", HudKeys.FIVE) },
    tr("Слот 6") to { key("slot6", "6", HudKeys.SIX) },
    tr("Слот 7") to { key("slot7", "7", HudKeys.SEVEN) },
    tr("Слот 8") to { key("slot8", "8", HudKeys.EIGHT) },
    tr("Слот 9") to { key("slot9", "9", HudKeys.NINE) },
    tr("Слот назад") to { wheel("slot-prev", tr("Слот −"), HudKeys.SCROLL_UP) },
    tr("Слот вперёд") to { wheel("slot-next", tr("Слот +"), HudKeys.SCROLL_DOWN) },
    tr("Удар") to { mouse("hit", tr("Удар"), HudKeys.MOUSE_LEFT) },
    tr("Действие") to { mouse("use", tr("Действие"), HudKeys.MOUSE_RIGHT) },
    tr("Средняя") to { mouse("middle", tr("Копировать"), HudKeys.MOUSE_MIDDLE) },
    tr("Клавиатура") to { action("keyboard", tr("Клавиатура"), HudAction.KEYBOARD, 140f) },
    tr("Меню") to { action("menu", tr("Меню"), HudAction.MENU, 96f) },
    tr("Курсор") to { action("mode", tr("Курсор"), HudAction.POINTER_MODE, 104f) },
    tr("Джойстик") to {
        HudControl("stick", tr("Ходьба"), HudKind.STICK, x = 0.5f, y = 0.5f, width = 176f, height = 176f)
    },
    tr("Джойстик под палец") to { HudPresets.base(HudKind.FLOAT_STICK).copy(x = 0.15f, y = 0.72f) },
    tr("Прыжок и полёт") to { HudPresets.base(HudKind.JUMP).copy(icon = HudIcons.JUMP) },
    tr("Инвентарь у хотбара") to { HudPresets.base(HudKind.HOTBAR_MORE).copy(icon = HudIcons.MORE) },
)

private fun key(
    id: String,
    label: String,
    code: Int,
    wide: Boolean = false,
    toggle: Boolean = false,
    visibility: HudVisibility = HudVisibility.LOOK,
): HudControl = HudControl(
    id = id,
    label = label,
    kind = HudKind.KEY,
    code = code,
    x = 0.5f,
    y = 0.5f,
    width = if (wide) 104f else 64f,
    height = 46f,
    toggle = toggle,
    visibility = visibility,
)

private fun wheel(id: String, label: String, notches: Int): HudControl = HudControl(
    id = id,
    label = label,
    kind = HudKind.SCROLL,
    code = notches,
    x = 0.5f,
    y = 0.5f,
    width = 80f,
    height = 46f,
)

private fun mouse(id: String, label: String, code: Int): HudControl = HudControl(
    id = id,
    label = label,
    kind = HudKind.MOUSE,
    code = code,
    x = 0.5f,
    y = 0.5f,
    width = 104f,
    height = 46f,
)

private fun action(id: String, label: String, what: HudAction, width: Float): HudControl = HudControl(
    id = id,
    label = label,
    kind = HudKind.ACTION,
    action = what,
    x = 0.5f,
    y = 0.5f,
    width = width,
    height = 44f,
    visibility = HudVisibility.ALWAYS,
)
