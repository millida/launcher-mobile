package net.millida.launcher.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.hud.HudAction
import net.millida.launcher.core.hud.HudControl
import net.millida.launcher.core.hud.HudIcons
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.hud.HudKind
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.core.hud.HudPresets
import net.millida.launcher.core.hud.HudProfiles
import net.millida.launcher.core.hud.HudTransfer
import net.millida.launcher.core.hud.HudVisibility
import java.util.Locale
import kotlin.math.roundToInt

private enum class EditorTab(val label: String) {
    CONTROL("Кнопка"),
    PROFILES("Раскладки"),
    TOUCH("Касание"),
}

private enum class PressMode(val label: String) {
    HOLD("Удержание"),
    TOGGLE("Переключатель"),
    LATCH("Двойной тап"),
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
    HudVisibility.LOOK -> "В мире"
    HudVisibility.WALKING -> "На земле"
    HudVisibility.FLYING -> "В полёте"
    HudVisibility.CURSOR -> "В меню"
    HudVisibility.ALWAYS -> "Всегда"
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
            MSecondaryButton("Показать", onClick = { hidden = false }, icon = MIcons.chevronUp, size = MButtonSize.Small)
            MPrimaryButton("Готово", onClick = onDone, icon = MIcons.check, size = MButtonSize.Small)
        }
        return
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
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
                "Управление",
                modifier = Modifier.weight(1f),
                style = MillidaType.dialogTitle,
                color = colors.fg,
                maxLines = 1,
            )
            MTextButton("Скрыть", onClick = { hidden = true }, icon = MIcons.chevronDown)
            Spacer(Modifier.width(8.dp))
            MPrimaryButton("Готово", onClick = onDone, icon = MIcons.check, size = MButtonSize.Small)
        }

        MSegmented(
            options = EditorTab.entries.toList(),
            selected = tab,
            onSelect = { tab = it },
            label = { it.label },
        )

        when (tab) {
            EditorTab.CONTROL -> ControlTab(layout, selected, onChange, onRemove, onAdd)
            EditorTab.PROFILES -> ProfilesTab(profiles, onProfiles, onPreset, onNotice)
            EditorTab.TOUCH -> TouchTab(layout, sensitivity, onLayout, onSensitivity)
        }
    }
}

@Composable
private fun ControlTab(
    layout: HudLayout,
    selected: HudControl?,
    onChange: (HudControl) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (HudControl) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (selected == null) {
        Text(
            "Перетащи кнопку пальцем, коснись её, чтобы настроить",
            style = MillidaType.small,
            color = millida.fgSubtle,
        )
    } else {
        SelectedControl(layout, selected, onChange, onRemove)
    }

    MSecondaryButton(
        if (adding) "Скрыть список" else "Добавить кнопку",
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
    onRemove: (String) -> Unit,
) {
    val colors = millida
    val canReset = (HudPresets.byName(layout.name) ?: HudPresets.bedrock()).controls.any { it.id == selected.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Кнопка «${selected.label}»",
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canReset) {
                MTextButton(
                    "Сбросить",
                    onClick = { layout.reset(selected.id).controls.firstOrNull { it.id == selected.id }?.let(onChange) },
                    icon = MIcons.refresh,
                )
                Spacer(Modifier.width(6.dp))
            }
            MDangerButton("Удалить", onClick = { onRemove(selected.id) }, icon = MIcons.trash)
        }
        EditorRow("Размер") {
            MSlider(
                value = selected.scale,
                onValueChange = { onChange(selected.withSize(it)) },
                valueRange = HudControl.MIN_SCALE..HudControl.MAX_SCALE,
            )
        }
        EditorRow("Прозрачность") {
            MSlider(
                value = selected.shownOpacity(layout.shownOpacity),
                onValueChange = { onChange(selected.withOpacity(it)) },
                valueRange = HudLayout.MIN_OPACITY..1f,
            )
        }
        if (selected.opacity != null) {
            MTextButton("Как у всех", onClick = { onChange(selected.withOpacity(null)) }, icon = MIcons.refresh)
        }
        MSegmented(
            options = listOf(false, true),
            selected = selected.hidden,
            onSelect = { onChange(selected.copy(hidden = it)) },
            label = { if (it) "Скрыта" else "Видна" },
        )
        if (!selected.anchored && selected.kind != HudKind.FLOAT_STICK) {
            MSectionTitle("КОГДА ВИДНА")
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
    MSectionTitle("КЛАВИШИ")
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
            "Сменить",
            onClick = { pick = if (pick == KeyPick.REPLACE) null else KeyPick.REPLACE },
            icon = MIcons.edit,
        )
        if (keys.size < HudControl.MAX_KEYS) {
            MTextButton(
                "Сочетание",
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
        if (next == profiles) onNotice("Раскладок уже ${HudProfiles.MAX_PROFILES} — удали лишнюю") else onProfiles(next)
    }

    fun commitRename() {
        onProfiles(profiles.rename(active, draft))
        renaming = false
    }

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profiles.names.forEach { name ->
            MChip(name, selected = name == active, onClick = {
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
                placeholder = "Название",
                modifier = Modifier.weight(1f),
                onImeAction = ::commitRename,
            )
            Spacer(Modifier.width(8.dp))
            MPrimaryButton("Сохранить", onClick = ::commitRename, icon = MIcons.check, size = MButtonSize.Small)
            MTextButton("Отмена", onClick = { renaming = false })
        }
        confirmDelete -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Удалить «$active»?",
                modifier = Modifier.weight(1f),
                style = MillidaType.body,
                color = millida.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MTextButton("Отмена", onClick = { confirmDelete = false })
            MDangerButton("Удалить", onClick = {
                confirmDelete = false
                onProfiles(profiles.delete(active))
            }, icon = MIcons.trash)
        }
        else -> Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MSecondaryButton("Новая", onClick = { create(profiles.create(HudProfiles.NEW_NAME)) }, icon = MIcons.plus, size = MButtonSize.Small)
            MSecondaryButton("Копия", onClick = { create(profiles.duplicate()) }, icon = MIcons.copy, size = MButtonSize.Small)
            MSecondaryButton("Переименовать", onClick = {
                draft = active
                renaming = true
            }, icon = MIcons.edit, size = MButtonSize.Small)
            MDangerButton("Удалить", onClick = { confirmDelete = true }, enabled = profiles.names.size > 1, icon = MIcons.trash)
        }
    }

    MSectionTitle("ШАБЛОН")
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

    MSectionTitle("ОБМЕН")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MSecondaryButton(
            "Скопировать",
            onClick = {
                clipboard.setText(AnnotatedString(HudTransfer.encode(profiles.layout)))
                onNotice("Раскладка скопирована")
            },
            icon = MIcons.copy,
            size = MButtonSize.Small,
        )
        MSecondaryButton(
            "Вставить",
            onClick = {
                val imported = clipboard.getText()?.text?.let(HudTransfer::decode)
                if (imported == null) {
                    onNotice("В буфере нет раскладки — скопируй её кнопкой «Скопировать»")
                } else {
                    create(profiles.create(imported.name, imported))
                }
            },
            icon = MIcons.download,
            size = MButtonSize.Small,
        )
    }
}

@Composable
private fun TouchTab(
    layout: HudLayout,
    sensitivity: Float,
    onLayout: (HudLayout) -> Unit,
    onSensitivity: (Float) -> Unit,
) {
    var look by remember(sensitivity) { mutableFloatStateOf(sensitivity) }
    EditorRow("Прозрачность") {
        MSlider(
            value = layout.shownOpacity,
            onValueChange = { onLayout(layout.withOpacity(it)) },
            valueRange = HudLayout.MIN_OPACITY..1f,
        )
    }
    MSwitchRow(
        "Тап бьёт",
        "Короткое касание — удар, а не действие",
        layout.tapHits,
    ) { hits -> onLayout(layout.withLookTap(if (hits) HudKeys.MOUSE_LEFT else HudKeys.MOUSE_RIGHT)) }
    EditorRow("Обзор ${String.format(Locale.ROOT, "%.1f×", look)}") {
        MSlider(
            value = look,
            onValueChange = { look = it },
            valueRange = 0.3f..3f,
            steps = 26,
            onValueChangeFinished = { onSensitivity(look) },
        )
    }
    EditorRow("Удержание ${layout.shownHoldDelay} мс") {
        MSlider(
            value = layout.shownHoldDelay.toFloat(),
            onValueChange = { onLayout(layout.withHoldDelay(((it / HOLD_STEP_MS).roundToInt() * HOLD_STEP_MS))) },
            valueRange = HudLayout.MIN_HOLD_MS.toFloat()..HudLayout.MAX_HOLD_MS.toFloat(),
            steps = (HudLayout.MAX_HOLD_MS - HudLayout.MIN_HOLD_MS) / HOLD_STEP_MS - 1,
        )
    }
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

private val addable: List<Pair<String, () -> HudControl>> = listOf(
    "Прыжок" to { key("jump", "Прыжок", HudKeys.SPACE, wide = true) },
    "Присесть" to { key("sneak", "Присесть", HudKeys.LEFT_SHIFT, wide = true, toggle = true) },
    "Бег" to { key("sprint", "Бег", HudKeys.LEFT_CONTROL, wide = true, toggle = true) },
    "W" to { key("forward", "W", HudKeys.W) },
    "A" to { key("left", "A", HudKeys.A) },
    "S" to { key("back", "S", HudKeys.S) },
    "D" to { key("right", "D", HudKeys.D) },
    "Инвентарь" to { key("inventory", "Инвентарь", HudKeys.E, wide = true) },
    "Чат" to { key("chat", "Чат", HudKeys.T, wide = true) },
    "Бросить" to { key("drop", "Бросить", HudKeys.Q, wide = true) },
    "Выбрать" to { key("pick", "Выбрать", HudKeys.F, wide = true) },
    "Меню игры" to { key("escape", "Esc", HudKeys.ESCAPE, wide = true, visibility = HudVisibility.ALWAYS) },
    "Список" to { key("tab", "Игроки", HudKeys.TAB, wide = true) },
    "Отладка" to { key("debug", "F3", HudKeys.F3, wide = true) },
    "Интерфейс F1" to { key("hide", "F1", HudKeys.F1) },
    "Скриншот F2" to { key("screenshot", "F2", HudKeys.F2) },
    "Вид F5" to { key("camera", "F5", HudKeys.F5) },
    "Слот 1" to { key("slot1", "1", HudKeys.ONE) },
    "Слот 2" to { key("slot2", "2", HudKeys.TWO) },
    "Слот 3" to { key("slot3", "3", HudKeys.THREE) },
    "Слот 4" to { key("slot4", "4", HudKeys.FOUR) },
    "Слот 5" to { key("slot5", "5", HudKeys.FIVE) },
    "Слот 6" to { key("slot6", "6", HudKeys.SIX) },
    "Слот 7" to { key("slot7", "7", HudKeys.SEVEN) },
    "Слот 8" to { key("slot8", "8", HudKeys.EIGHT) },
    "Слот 9" to { key("slot9", "9", HudKeys.NINE) },
    "Слот назад" to { wheel("slot-prev", "Слот −", HudKeys.SCROLL_UP) },
    "Слот вперёд" to { wheel("slot-next", "Слот +", HudKeys.SCROLL_DOWN) },
    "Удар" to { mouse("hit", "Удар", HudKeys.MOUSE_LEFT) },
    "Действие" to { mouse("use", "Действие", HudKeys.MOUSE_RIGHT) },
    "Средняя" to { mouse("middle", "Копировать", HudKeys.MOUSE_MIDDLE) },
    "Клавиатура" to { action("keyboard", "Клавиатура", HudAction.KEYBOARD, 140f) },
    "Меню" to { action("menu", "Меню", HudAction.MENU, 96f) },
    "Курсор" to { action("mode", "Курсор", HudAction.POINTER_MODE, 104f) },
    "Джойстик" to {
        HudControl("stick", "Ходьба", HudKind.STICK, x = 0.5f, y = 0.5f, width = 176f, height = 176f)
    },
    "Джойстик под палец" to { HudPresets.base(HudKind.FLOAT_STICK).copy(x = 0.15f, y = 0.72f) },
    "Прыжок и полёт" to { HudPresets.base(HudKind.JUMP).copy(icon = HudIcons.JUMP) },
    "Инвентарь у хотбара" to { HudPresets.base(HudKind.HOTBAR_MORE).copy(icon = HudIcons.MORE) },
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
