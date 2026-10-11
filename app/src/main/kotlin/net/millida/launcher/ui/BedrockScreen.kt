package net.millida.launcher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Prefs
import net.millida.launcher.UiState
import net.millida.launcher.core.bedrock.VIAPROXY_SIZE
import net.millida.launcher.ui.settings.SettingHint
import net.millida.launcher.ui.settings.SettingsSection
import net.millida.launcher.ui.settings.rememberTrackedAction
import net.millida.launcher.ui.settings.stepIn
import kotlin.math.roundToInt

private const val GALAXY_STORE_URL = "https://galaxystore.samsung.com/detail/com.mojang.minecraftpe"
private const val OFFICIAL_SITE_URL = "https://www.minecraft.net/ru-ru/store/minecraft-deluxe-collection-pc"

/**
 * Bedrock is a different game, not a different version: it opens either in its
 * own client or in our Java client behind a protocol translator.
 */
@Composable
fun BedrockScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var lifecycleSeen by remember { mutableStateOf(false) }
    val remove = rememberTrackedAction()

    // The store is another app: coming back from it is the moment the official client may have appeared.
    // Opening the screen already refreshes, so only a later return does.
    LaunchedEffect(lifecycleState) {
        if (lifecycleSeen && lifecycleState == Lifecycle.State.RESUMED) model.refreshBedrock()
        lifecycleSeen = true
    }

    MScreen(
        modifier = modifier,
        title = "Bedrock",
        subtitle = "Серверы на порту 19132",
        onBack = { model.back() },
    ) {
        item(key = "bedrock-mode") { ModeSection(state, model, stepIn()) }
        item(key = "bedrock-official") { OfficialSection(state, model, context, stepIn()) }
        item(key = "bedrock-bridge") {
            BridgeSection(
                state = state,
                model = model,
                removing = remove.running,
                onRemove = { confirmRemove = true },
                modifier = stepIn(),
            )
        }
        item(key = "bedrock-how") { HowSection(stepIn()) }
    }

    if (confirmRemove) {
        MConfirmDialog(
            title = "Удалить переводчик?",
            text = "Bedrock-серверы будут открываться только официальным клиентом",
            confirm = "Удалить",
            onConfirm = {
                confirmRemove = false
                remove.start { model.removeBedrockBridge() }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun ModeSection(state: UiState, model: LauncherViewModel, modifier: Modifier) {
    SettingsSection("ЧЕМ ОТКРЫВАТЬ", modifier) {
        MSegmented(
            options = listOf(Prefs.BEDROCK_ASK, Prefs.BEDROCK_OFFICIAL, Prefs.BEDROCK_OWN),
            selected = state.bedrockMode,
            onSelect = model::setBedrockMode,
            label = { mode ->
                when (mode) {
                    Prefs.BEDROCK_OFFICIAL -> "Mojang"
                    Prefs.BEDROCK_OWN -> "Наш"
                    else -> "Авто"
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        SettingHint(
            when (state.bedrockMode) {
                Prefs.BEDROCK_OFFICIAL -> "Всегда игра Bedrock Edition"
                Prefs.BEDROCK_OWN -> "Всегда наш Java-клиент"
                else -> "Что установлено: сначала Mojang, потом наш"
            },
        )
    }
}

@Composable
private fun OfficialSection(state: UiState, model: LauncherViewModel, context: Context, modifier: Modifier) {
    val ready = state.bedrockOfficialReady
    SettingsSection("ОФИЦИАЛЬНЫЙ КЛИЕНТ", modifier) {
        MListRow(
            title = "Bedrock Edition",
            subtitle = if (ready) "Установлена на телефоне" else "Платная, ставится из магазина",
            icon = MIcons.blocks,
            plate = false,
            trailing = { ReadyBadge(ready, if (ready) "Есть" else "Нет") },
        )
        Spacer(Modifier.height(8.dp))
        if (ready) {
            MPrimaryButton("Открыть игру", onClick = model::openBedrockEdition, icon = MIcons.play)
        } else {
            MButtonRow {
                MPrimaryButton(
                    "Google Play",
                    onClick = model::installBedrockEdition,
                    icon = MIcons.download,
                    size = MButtonSize.Small,
                )
                MSecondaryButton(
                    "Galaxy Store",
                    onClick = { model.openLinkOrNotice(context, GALAXY_STORE_URL) },
                    size = MButtonSize.Small,
                )
                MTextButton(
                    "minecraft.net",
                    onClick = { model.openLinkOrNotice(context, OFFICIAL_SITE_URL) },
                    icon = MIcons.externalLink,
                )
            }
        }
    }
}

@Composable
private fun BridgeSection(
    state: UiState,
    model: LauncherViewModel,
    removing: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val colors = millida
    val install = rememberTrackedAction()
    val ready = state.bedrockBridgeReady
    val progress = state.bedrockBridgeProgress
    val sizeMb = VIAPROXY_SIZE / 1024 / 1024

    SettingsSection("НАШ КЛИЕНТ", modifier) {
        MListRow(
            title = "Java + переводчик",
            subtitle = if (ready) {
                "Протокол ${state.bedrockTargetVersion.ifEmpty { "определяется" }}"
            } else {
                "Твой скин и управление, вторая игра не нужна"
            },
            icon = MIcons.link,
            plate = false,
            trailing = { ReadyBadge(ready, if (ready) "Готов" else "$sizeMb МБ") },
        )
        Spacer(Modifier.height(8.dp))
        when {
            progress > 0f -> {
                MProgressBar(progress)
                Spacer(Modifier.height(6.dp))
                SettingHint("Скачиваем: ${(progress * 100).roundToInt()}%")
            }
            ready -> MDangerButton(
                "Удалить",
                onClick = onRemove,
                icon = MIcons.trash,
                enabled = state.busy == null && !removing,
            )
            else -> MPrimaryButton(
                "Скачать",
                onClick = { install.start { model.installBedrockBridge() } },
                icon = MIcons.download,
                enabled = state.busy == null,
                loading = install.running,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.info, size = 16.dp, color = colors.fgSubtle)
            Spacer(Modifier.width(8.dp))
            SettingHint("С проверкой Xbox вход на чужой сервер может не пройти", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReadyBadge(ready: Boolean, text: String) {
    val colors = millida
    MBadge(
        text,
        if (ready) colors.accentText else colors.fgSubtle,
        if (ready) colors.accentSoft else colors.surface2,
    )
}

@Composable
private fun HowSection(modifier: Modifier) {
    SettingsSection("КАК ЭТО РАБОТАЕТ", modifier) {
        StepRow(1, "Найди Bedrock-сервер", "Порт 19132, метка «Bedrock»")
        MDivider()
        StepRow(2, "Нажми «Играть»", "Откроется выбранным способом")
        MDivider()
        StepRow(3, "Для нашего клиента", "Нужна сборка той же версии")
    }
}

@Composable
private fun StepRow(number: Int, title: String, subtitle: String) {
    val colors = millida
    MListRow(
        title = title,
        subtitle = subtitle,
        plate = false,
        leading = {
            Box(
                Modifier
                    .size(40.dp)
                    .pixelSurface(colors.accentSoft, MillidaCut.control)
                    .padding(bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    number.toString(),
                    style = MillidaType.pixelLarge.copy(fontSize = 18.sp, lineHeight = 20.sp),
                    color = colors.accentText,
                )
            }
        },
    )
}
