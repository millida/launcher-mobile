package net.millida.launcher.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.input.GamepadMap

/** Each gamepad button with what it presses in the game; a tap opens the choices for it. */
@Composable
fun GamepadSheet(model: LauncherViewModel, onDismiss: () -> Unit) {
    var map by remember { mutableStateOf(model.gamepadMap()) }
    var editing by remember { mutableStateOf<Int?>(null) }

    fun save(next: GamepadMap) {
        map = next
        model.saveGamepadMap(next)
    }

    MBottomSheet(onDismiss = onDismiss, title = tr("Геймпад")) {
        SettingHintText(tr("Стики: левый — ходьба, правый — обзор. Кнопки меняются здесь и действуют со следующего запуска игры"))
        Spacer(Modifier.height(8.dp))
        GamepadMap.buttons.forEach { button ->
            val current = map.binding(button.keyCode)
            MListRow(
                title = button.label,
                subtitle = current?.let { GamepadMap.label(it) } ?: tr("Ничего"),
                plate = false,
                onClick = { editing = if (editing == button.keyCode) null else button.keyCode },
            )
            if (editing == button.keyCode) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GamepadMap.choices.forEach { choice ->
                        MChip(
                            GamepadMap.label(choice),
                            selected = choice == current,
                            onClick = {
                                save(map.with(button.keyCode, choice))
                                editing = null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (map.customized) {
            Spacer(Modifier.height(10.dp))
            MTextButton(tr("Как было"), onClick = { save(GamepadMap()) }, icon = MIcons.refresh)
        }
    }
}

@Composable
private fun SettingHintText(text: String) {
    androidx.compose.material3.Text(text, style = MillidaType.small, color = millida.fgSubtle)
}
