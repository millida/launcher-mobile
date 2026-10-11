package net.millida.launcher.ui.catalog

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSectionTitle
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.humanSize
import net.millida.launcher.ui.millida

@Composable
internal fun TargetPickerSheet(
    profiles: List<Profile>,
    selectedId: String?,
    onPick: (Profile) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    MBottomSheet(onDismiss = onDismiss, title = "Куда ставить") {
        profiles.forEachIndexed { index, profile ->
            if (index > 0) MDivider()
            MListRow(
                title = profile.name,
                subtitle = profile.label,
                icon = MIcons.box,
                onClick = { onPick(profile) },
                plate = false,
                trailing = {
                    if (profile.id == selectedId) MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
                },
            )
        }
    }
}

/**
 * A mod that needs a library the build does not have starts the game and
 * stops it again on the first tick. The whole list is shown before anything
 * is written, so the player agrees to it once.
 */
@Composable
internal fun DependencySheet(
    ask: DependencyAsk,
    installing: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    val plan = ask.plan
    MBottomSheet(onDismiss = onDismiss, title = "Нужно ещё кое-что") {
        Text(
            "«${plan.title}» без этого не запустится",
            style = MillidaType.body,
            color = colors.fgMuted,
        )
        Spacer(Modifier.height(10.dp))
        plan.missing.forEachIndexed { index, node ->
            if (index > 0) MDivider()
            MListRow(
                title = node.title,
                subtitle = listOf(node.versionNumber, if (node.sizeBytes > 0) humanSize(node.sizeBytes) else "")
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifEmpty { null },
                icon = MIcons.box,
                plate = false,
            )
        }
        if (plan.unresolved.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            MSectionTitle("НЕ НАШЛОСЬ")
            Spacer(Modifier.height(6.dp))
            plan.unresolved.forEach { missing ->
                Text(
                    "${missing.title} — ${missing.problem}",
                    style = MillidaType.small,
                    color = colors.danger,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        if (plan.satisfied.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Уже стоит: ${plan.satisfied.joinToString(", ")}",
                style = MillidaType.small,
                color = colors.fgFaint,
            )
        }
        Spacer(Modifier.height(18.dp))
        MPrimaryButton(
            text = if (plan.missing.size > 1) "Поставить всё" else "Поставить",
            onClick = onInstall,
            modifier = Modifier.fillMaxWidth(),
            enabled = plan.missing.isNotEmpty(),
            icon = MIcons.download,
            size = MButtonSize.Large,
            loading = installing,
        )
        Spacer(Modifier.height(6.dp))
        MTextButton(text = "Не надо", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
