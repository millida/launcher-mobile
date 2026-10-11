package net.millida.launcher.ui.hosting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import net.millida.launcher.Area
import net.millida.launcher.HostingModel
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.hosting.HOSTING_NAME_MAX
import net.millida.launcher.core.hosting.HostingField
import net.millida.launcher.core.hosting.HostingFieldGroup
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.hosting.HostingSettingsSpec
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MSwitchRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

private const val SEGMENTED_MAX = 4

/** The desktop panel's «Настройки»: the everyday rules on top, the rest behind «Для опытных». */
@Composable
fun HostingSettingsTab(
    state: UiState,
    model: LauncherViewModel,
    hosting: HostingModel,
    server: HostingServerState?,
    saving: String?,
    running: Boolean,
) {
    val serverId = state.hostingServerId
    val details = state.hostingDetails
    val error = state.errorOf(Area.HOSTED_SERVER)
    var advanced by rememberSaveable(serverId) { mutableStateOf(false) }
    ToolsList {
        if (details == null) {
            item(key = "settings-wait") {
                if (error != null) {
                    MErrorCard(error, onRetry = { model.retry(Area.HOSTED_SERVER) })
                } else {
                    MSkeleton(height = 280.dp)
                }
            }
            return@ToolsList
        }
        val rules = details.settings
        val save: (String, JsonElement) -> Unit = { key, value -> hosting.saveSetting(serverId, key, value, running) }
        val maxSlots = server?.planMaxPlayers?.takeIf { it > 0 } ?: 1000
        item(key = "settings-main") {
            MCard(contentPadding = 14.dp) {
                NameRow(
                    current = server?.name.orEmpty(),
                    busy = false,
                    onSave = { name -> hosting.rename(serverId, name) {} },
                )
                FieldGroup(HostingSettingsSpec.main(maxSlots), rules, saving, save)
            }
        }
        item(key = "settings-advanced-toggle") {
            MSecondaryButton(
                text = "Для опытных",
                onClick = { advanced = !advanced },
                modifier = Modifier.fillMaxWidth(),
                icon = if (advanced) MIcons.chevronUp else MIcons.chevronDown,
            )
        }
        if (advanced) {
            HostingSettingsSpec.advanced(server?.free == true).forEach { group ->
                item(key = "settings-group-${group.title}") {
                    MCard(contentPadding = 14.dp) {
                        FieldGroup(group, rules, saving, save)
                    }
                }
            }
        }
        if (server?.pendingRestart?.isNotEmpty() == true) {
            item(key = "settings-restart") {
                MCard(contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MIcon(MIcons.refresh, size = 18.dp, color = millida.accentText)
                        Spacer(Modifier.width(10.dp))
                        Text("Нужен перезапуск", modifier = Modifier.weight(1f), style = MillidaType.rowTitle, color = millida.fg)
                        if (running) {
                            MSecondaryButton(
                                text = "Перезапустить",
                                onClick = { hosting.power(serverId, HostingModel.RESTART) },
                                size = MButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameRow(current: String, busy: Boolean, onSave: (String) -> Unit) {
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    FieldTitle("Название", null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        MField(
            value = draft,
            onValueChange = { draft = it.take(HOSTING_NAME_MAX) },
            placeholder = current,
            modifier = Modifier.weight(1f),
            enabled = !busy,
            onImeAction = { if (draft.isNotBlank() && draft.trim() != current) onSave(draft.trim()) },
        )
        Spacer(Modifier.width(8.dp))
        MIconButton(
            MIcons.check,
            onClick = { onSave(draft.trim()) },
            enabled = draft.isNotBlank() && draft.trim() != current,
            label = "Сохранить",
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FieldGroup(group: HostingFieldGroup, rules: Map<String, String>, saving: String?, save: (String, JsonElement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        group.title?.let { Text(it, style = MillidaType.sectionTitle, color = millida.fg) }
        group.fields.filter { HostingSettingsSpec.visible(it, rules) }.forEach { field ->
            val busy = saving != null
            when (field) {
                is HostingField.Toggle -> MSwitchRow(
                    title = field.title,
                    hint = field.hint,
                    checked = HostingSettingsSpec.toggle(rules, field.key, field.default),
                    onChange = { if (!busy) save(field.key, JsonPrimitive(it)) },
                )
                is HostingField.Choice -> ChoiceRow(field, HostingSettingsSpec.choice(rules, field), busy) { value ->
                    save(field.key, if (field.key == "javaVersion") javaValue(value) else JsonPrimitive(value))
                }
                is HostingField.Number -> TextRow(
                    title = field.title,
                    hint = field.hint,
                    current = HostingSettingsSpec.number(rules, field).toString(),
                    placeholder = field.default.toString(),
                    numeric = true,
                    busy = busy,
                    accept = { text -> HostingSettingsSpec.parseNumber(text, field)?.let { JsonPrimitive(it) } },
                    onSave = { save(field.key, it) },
                )
                is HostingField.Text -> TextRow(
                    title = field.title,
                    hint = field.hint,
                    current = HostingSettingsSpec.raw(rules, field.key).orEmpty(),
                    placeholder = field.placeholder,
                    numeric = false,
                    busy = busy,
                    accept = { text -> JsonPrimitive(text.trim()) },
                    onSave = { save(field.key, it) },
                )
            }
        }
    }
}

/** «Автоматически» is no pinned Java at all, so it clears the rule rather than sending a zero. */
private fun javaValue(value: String): JsonElement = value.toIntOrNull()?.takeIf { it > 0 }?.let { JsonPrimitive(it) } ?: JsonNull

@Composable
private fun FieldTitle(title: String, hint: String?) {
    Text(title, style = MillidaType.rowTitle, color = millida.fg)
    if (hint != null) Text(hint, style = MillidaType.small, color = millida.fgSubtle)
}

@Composable
private fun ChoiceRow(field: HostingField.Choice, value: String, busy: Boolean, onPick: (String) -> Unit) {
    var open by rememberSaveable(field.key) { mutableStateOf(false) }
    FieldTitle(field.title, field.hint)
    if (field.options.size <= SEGMENTED_MAX) {
        MSegmented(
            options = field.options.map { it.first },
            selected = value,
            onSelect = { if (!busy && it != value) onPick(it) },
            label = { key -> field.options.firstOrNull { it.first == key }?.second ?: key },
        )
    } else {
        MChip(
            text = field.options.firstOrNull { it.first == value }?.second ?: value,
            selected = false,
            onClick = { open = true },
            icon = MIcons.chevronDown,
            enabled = !busy,
        )
        if (open) {
            MBottomSheet(onDismiss = { open = false }, title = field.title) {
                field.options.forEach { (key, label) ->
                    MListRow(
                        title = label,
                        plate = false,
                        onClick = {
                            open = false
                            if (key != value) onPick(key)
                        },
                        trailing = { if (key == value) MIcon(MIcons.check, size = 18.dp, color = millida.accentText) },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TextRow(
    title: String,
    hint: String?,
    current: String,
    placeholder: String,
    numeric: Boolean,
    busy: Boolean,
    accept: (String) -> JsonElement?,
    onSave: (JsonElement) -> Unit,
) {
    var draft by rememberSaveable(title, current) { mutableStateOf(current) }
    val parsed = accept(draft)
    val changed = draft.trim() != current.trim() && parsed != null
    val commit = { if (changed && !busy && parsed != null) onSave(parsed) }
    FieldTitle(title, hint)
    Row(verticalAlignment = Alignment.CenterVertically) {
        MField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            onImeAction = commit,
        )
        Spacer(Modifier.width(8.dp))
        MIconButton(MIcons.check, onClick = commit, enabled = changed && !busy, label = "Сохранить")
    }
    Spacer(Modifier.height(4.dp))
}
