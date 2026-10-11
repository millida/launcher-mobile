package net.millida.launcher.ui.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.catalog.AiBuilder
import net.millida.launcher.core.catalog.AiMod
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MBusyCard
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MDivider
import net.millida.launcher.ui.MField
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSignInGate
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MTextButton
import net.millida.launcher.ui.MToggle
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

private const val SKELETON_ROWS = 5

/** The AI builder: a request in words, then the plan to tick through, then one press for the whole build. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiBuilderSheet(
    ai: AiBuilderState,
    signedIn: Boolean,
    job: ModpackJob?,
    busy: Boolean,
    onPrompt: (String) -> Unit,
    onBuild: () -> Unit,
    onTitle: (String) -> Unit,
    onToggle: (AiMod) -> Unit,
    onCreate: () -> Unit,
    onReset: () -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = millida
    val plan = ai.plan
    MBottomSheet(onDismiss = onDismiss, title = "Собрать с ИИ") {
        if (!signedIn) {
            MSignInGate(onSignIn = onSignIn, title = "ИИ-сборщик — в аккаунте Millida")
            return@MBottomSheet
        }
        if (job != null) {
            MBusyCard(label = "Ставим «${job.title}»", progress = job.progress, note = job.stage)
            return@MBottomSheet
        }
        if (plan == null) {
            MField(
                value = ai.prompt,
                onValueChange = onPrompt,
                placeholder = "Хоррор с зомби на 1.20.1",
                leadingIcon = MIcons.sparkle,
                singleLine = false,
                maxLines = 3,
                imeAction = ImeAction.Done,
                enabled = !ai.building,
                clearable = true,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiBuilder.EXAMPLES.forEach { example ->
                    MChip(text = example, selected = false, onClick = { onPrompt(example) }, enabled = !ai.building)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        ai.soon -> "Скоро"
                        ai.quota != null -> "${ai.quota.remaining} из ${ai.quota.limit} сегодня"
                        else -> ""
                    },
                    style = MillidaType.small,
                    color = colors.fgFaint,
                    modifier = Modifier.weight(1f),
                )
                MPrimaryButton(
                    text = if (ai.building) "Собираем" else "Собрать",
                    onClick = onBuild,
                    enabled = ai.ready && !ai.soon && !ai.building,
                    loading = ai.building,
                    icon = MIcons.sparkle,
                )
            }
            ai.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(MIcons.alert, size = 18.dp, color = colors.danger)
                    Spacer(Modifier.width(8.dp))
                    Text(error, style = MillidaType.small, color = colors.danger, modifier = Modifier.weight(1f))
                    if (AiBuilder.retryable(error)) {
                        MTextButton(text = "Повторить", onClick = onBuild)
                    }
                }
            }
            if (ai.building) {
                Spacer(Modifier.height(12.dp))
                repeat(SKELETON_ROWS) {
                    MSkeletonRow()
                    Spacer(Modifier.height(8.dp))
                }
            }
            return@MBottomSheet
        }
        val chosen = ai.chosen
        val chosenCount = chosen?.let { it.mods.size + it.resourcepacks.size + it.shaders.size } ?: 0
        MField(value = ai.title, onValueChange = onTitle, placeholder = "Название сборки", leadingIcon = MIcons.edit)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MBadge(plan.mcVersion, colors.fgMuted, colors.surface2)
            MBadge(plan.loader.label, colors.fgMuted, colors.surface2)
            MBadge("$chosenCount из ${plan.all.size}", colors.accentText, colors.accentSoft)
        }
        if (plan.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(plan.notes, style = MillidaType.body, color = colors.fgMuted)
        }
        val excluded = AiBuilder.excludedLines(plan.excluded)
        if (excluded.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Не вошли: ${excluded.joinToString("; ")}", style = MillidaType.small, color = colors.fgFaint)
        }
        Spacer(Modifier.height(10.dp))
        val rows = plan.mods.map { it to (if (it.base) "база" else null) } +
            plan.resourcepacks.map { it to "ресурспак" } +
            plan.shaders.map { it to "шейдер" }
        rows.forEachIndexed { index, (mod, tag) ->
            if (index > 0) MDivider()
            val locked = mod.slug in AiBuilder.LOCKED_SLUGS
            MListRow(
                title = if (tag != null) "${mod.title} · $tag" else mod.title,
                subtitle = mod.why.ifBlank { null },
                plate = false,
                leading = { MRemoteImage(url = mod.icon, icon = MIcons.box, size = 40.dp) },
                trailing = {
                    MToggle(
                        checked = locked || mod.projectId !in ai.off,
                        onChange = { onToggle(mod) },
                        enabled = !locked && !busy,
                    )
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        MPrimaryButton(
            text = "Создать сборку",
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
            enabled = chosenCount > 0 && !busy,
            icon = MIcons.download,
            size = MButtonSize.Large,
        )
        Spacer(Modifier.height(8.dp))
        MSecondaryButton(text = "Заново", onClick = onReset, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    }
}
