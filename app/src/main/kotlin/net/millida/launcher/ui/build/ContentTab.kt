package net.millida.launcher.ui.build

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.content.SafetyReport
import net.millida.launcher.core.content.SafetyVerdict
import net.millida.launcher.core.content.UpdatePlan
import net.millida.launcher.core.cosmetics.CosmeticsModStatus
import net.millida.launcher.core.cosmetics.SkinDiagnosis
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MBadge
import net.millida.launcher.ui.MButtonRow
import net.millida.launcher.ui.MButtonSize
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MChip
import net.millida.launcher.ui.MConfirmDialog
import net.millida.launcher.ui.MEmptyState
import net.millida.launcher.ui.MErrorCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MPixelLoader
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MSegmented
import net.millida.launcher.ui.MSkeletonRow
import net.millida.launcher.ui.MToggle
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.home.LobbyState
import net.millida.launcher.ui.home.itemMotion
import net.millida.launcher.ui.humanSize
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame

private const val PLAN_PREVIEW = 6
private const val SAFETY_PREVIEW = 8

private fun contentLabel(kind: ContentKind): String = when (kind) {
    ContentKind.MOD -> "Моды"
    ContentKind.RESOURCE_PACK -> "Ресурсы"
    ContentKind.SHADER -> "Шейдеры"
    ContentKind.DATA_PACK -> "Датапаки"
}

private fun contentIcon(kind: ContentKind): MillidaIcon = when (kind) {
    ContentKind.MOD -> MIcons.box
    ContentKind.RESOURCE_PACK -> MIcons.image
    ContentKind.SHADER -> MIcons.sun
    ContentKind.DATA_PACK -> MIcons.book
}

private fun emptyTitle(kind: ContentKind): String = when (kind) {
    ContentKind.MOD -> "Модов пока нет"
    ContentKind.RESOURCE_PACK -> "Ресурсов пока нет"
    ContentKind.SHADER -> "Шейдеров пока нет"
    ContentKind.DATA_PACK -> "Датапаков пока нет"
}

private fun cosmeticsLabel(status: CosmeticsModStatus): String = when (status) {
    CosmeticsModStatus.INSTALLED -> "Стоит в сборке"
    CosmeticsModStatus.OUTDATED -> "Есть версия посвежее"
    CosmeticsModStatus.DISABLED -> "Стоит, но выключена"
    CosmeticsModStatus.MISSING -> "Ещё не стоит"
    CosmeticsModStatus.UNSUPPORTED_LOADER -> "Не подходит загрузчику этой сборки"
    CosmeticsModStatus.UNSUPPORTED_VERSION -> "Нет сборки под эту версию игры"
    CosmeticsModStatus.CATALOG_INCOMPLETE -> "Список версий не прочитался"
}

internal fun LazyListScope.contentTab(
    profile: Profile,
    state: UiState,
    lobby: LobbyState,
    model: LauncherViewModel,
    onAddFile: () -> Unit,
) {
    val kind = state.buildKind
    val busy = state.busy != null
    item(key = "content:kinds", contentType = "kinds") {
        if (LocalWideLayout.current) {
            MSegmented(
                options = ContentKind.entries,
                selected = kind,
                onSelect = { model.setBuildKind(it) },
                label = ::contentLabel,
            )
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContentKind.entries.forEach { option ->
                    MChip(
                        text = contentLabel(option),
                        selected = option == kind,
                        onClick = { model.setBuildKind(option) },
                        icon = contentIcon(option),
                    )
                }
            }
        }
    }
    item(key = "content:actions", contentType = "actions") {
        ContentActions(profile, kind, busy, lobby.importing, model, onAddFile)
    }
    if (kind == ContentKind.MOD) {
        item(key = "content:cosmetics", contentType = "cosmetics") { CosmeticsRow(profile, state, model) }
        val diagnosis = state.skinDiagnosis?.takeIf { it.profileId == profile.id && !it.ok }
        if (diagnosis != null) {
            item(key = "content:skin", contentType = "skin") { SkinDiagnosisCard(profile, diagnosis, busy, model) }
        }
    }
    val plan = state.contentUpdatePlan?.takeIf { it.kind == kind }
    if (plan != null) {
        item(key = "content:updates", contentType = "updates") { UpdatePlanCard(profile, plan, busy, model) }
    }
    val report = state.safetyReport?.takeIf { kind == ContentKind.MOD }
    if (report != null) {
        item(key = "content:safety", contentType = "safety") { SafetyCard(profile, report, busy, model) }
    }

    val files = state.buildContent.filter { it.kind == kind }
    val error = state.errorOf(Area.BUILD)
    when {
        files.isEmpty() && state.isLoading(Area.BUILD) ->
            items(3, key = { "content:skeleton:$it" }, contentType = { "skeleton" }) { MSkeletonRow() }

        files.isEmpty() && error != null -> item(key = "content:error", contentType = "error") {
            MErrorCard(error, onRetry = { model.retry(Area.BUILD) })
        }

        files.isEmpty() -> item(key = "content:empty", contentType = "empty") {
            MEmptyState(
                icon = contentIcon(kind),
                title = emptyTitle(kind),
                actionText = "Каталог",
                onAction = { model.openCatalogFromBuild() },
                actionIcon = MIcons.catalog,
            )
        }

        else -> {
            if (error != null) {
                item(key = "content:error", contentType = "error") {
                    MErrorCard(error, onRetry = { model.retry(Area.BUILD) })
                }
            }
            val updates = plan?.candidates?.map { it.file.name }?.toSet().orEmpty()
            items(files, key = { "file:${it.name}" }, contentType = { "file" }) { file ->
                ContentRow(
                    file = file,
                    hasUpdate = file.name in updates,
                    onToggle = { model.toggleContent(file) },
                    onDelete = { model.deleteContent(file) },
                    modifier = itemMotion(),
                )
            }
        }
    }
}

/**
 * On a wide window the adding buttons and the checks part to the two ends of
 * the line, as the desktop's `.act-row` does, and fold under each other only
 * when the line is too short for both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentActions(
    profile: Profile,
    kind: ContentKind,
    busy: Boolean,
    importing: Boolean,
    model: LauncherViewModel,
    onAddFile: () -> Unit,
) {
    val adding: @Composable () -> Unit = {
        MSecondaryButton(
            "Добавить файл",
            onClick = onAddFile,
            icon = MIcons.upload,
            size = MButtonSize.Small,
            loading = importing,
        )
        MSecondaryButton(
            "Каталог",
            onClick = { model.openCatalogFromBuild() },
            icon = MIcons.catalog,
            size = MButtonSize.Small,
        )
    }
    val checks: @Composable () -> Unit = {
        MSecondaryButton(
            "Обновления",
            onClick = { model.planContentUpdates(profile) },
            enabled = !busy,
            icon = MIcons.refresh,
            size = MButtonSize.Small,
        )
        MSecondaryButton(
            "Проверить",
            onClick = { model.scanSafety(profile) },
            enabled = !busy,
            icon = MIcons.shield,
            size = MButtonSize.Small,
        )
    }
    if (!LocalWideLayout.current) {
        MButtonRow {
            adding()
            if (kind == ContentKind.MOD) checks()
        }
        return
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { adding() }
        if (kind == ContentKind.MOD) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { checks() }
        }
    }
}

@Composable
private fun ContentRow(
    file: ContentFile,
    hasUpdate: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    var confirming by rememberSaveable(file.name) { mutableStateOf(false) }
    val title = file.title.ifEmpty { file.name }
    val meta = listOf(file.version, humanSize(file.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pixelSurface(colors.surface, MillidaCut.plate, colors.plateFrame())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .pixelSurface(if (file.enabled) colors.accentSoft else colors.surface2, MillidaCut.control),
            contentAlignment = Alignment.Center,
        ) {
            MIcon(contentIcon(file.kind), size = 24.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MillidaType.rowTitle,
                color = if (file.enabled) colors.fg else colors.fgFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasUpdate) {
                    Text(
                        "Обновление",
                        modifier = Modifier
                            .pixelSurface(colors.accentSoft, MillidaCut.badge)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MillidaType.tabLabel,
                        color = colors.accentText,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    meta,
                    style = MillidaType.small,
                    color = colors.fgSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MToggle(checked = file.enabled, onChange = { onToggle() })
            MIconButton(
                icon = MIcons.trash,
                onClick = { confirming = true },
                label = "Удалить",
            )
        }
    }
    if (confirming) {
        MConfirmDialog(
            title = "Удалить из сборки?",
            text = "«$title» пропадёт из этой сборки.",
            confirm = "Удалить",
            onConfirm = {
                confirming = false
                onDelete()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun CosmeticsRow(profile: Profile, state: UiState, model: LauncherViewModel) {
    val colors = millida
    LaunchedEffect(profile.id) { model.loadCosmetics(profile) }
    val cosmetics = state.cosmetics?.takeIf { it.profileId == profile.id }
    val busy = state.busy != null
    var removing by rememberSaveable(profile.id) { mutableStateOf(false) }
    val line = if (cosmetics == null) {
        "Проверяем сборку"
    } else if (!cosmetics.installed && !cosmetics.canInstall && cosmetics.reason.isNotEmpty()) {
        cosmetics.reason
    } else {
        cosmeticsLabel(cosmetics.status)
    }
    MListRow(
        title = "Косметика Millida",
        subtitle = line,
        leading = {
            Box(
                Modifier
                    .size(40.dp)
                    .pixelSurface(colors.accentDeep, MillidaCut.control, dots = true),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.cape, size = 24.dp)
            }
        },
        trailing = {
            if (cosmetics == null) {
                MPixelLoader()
            } else {
                if (cosmetics.status == CosmeticsModStatus.OUTDATED) {
                    MSecondaryButton(
                        "Обновить",
                        onClick = { model.installCosmetics(profile) },
                        enabled = !busy,
                        size = MButtonSize.Small,
                    )
                } else if (cosmetics.installed) {
                    MToggle(
                        checked = cosmetics.installedEnabled,
                        onChange = { model.setCosmeticsEnabled(profile, it) },
                        enabled = !busy,
                    )
                } else if (cosmetics.canInstall) {
                    MSecondaryButton(
                        "Поставить",
                        onClick = { model.installCosmetics(profile) },
                        enabled = !busy,
                        icon = MIcons.download,
                        size = MButtonSize.Small,
                    )
                }
                if (cosmetics.installed) {
                    MIconButton(
                        icon = MIcons.trash,
                        onClick = { removing = true },
                        enabled = !busy,
                        label = "Убрать косметику",
                    )
                }
            }
        },
    )
    if (removing) {
        MConfirmDialog(
            title = "Убрать косметику?",
            text = "Скин и плащ будут видны только в лаунчере.",
            confirm = "Убрать",
            onConfirm = {
                removing = false
                model.removeCosmetics(profile)
            },
            onDismiss = { removing = false },
        )
    }
}

@Composable
private fun SkinDiagnosisCard(profile: Profile, diagnosis: SkinDiagnosis, busy: Boolean, model: LauncherViewModel) {
    val colors = millida
    MCard {
        Text("Скина нет в игре?", style = MillidaType.rowTitle, color = colors.fg)
        if (diagnosis.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(diagnosis.summary, style = MillidaType.small, color = colors.fgSubtle)
        }
        diagnosis.checks.filterNot { it.ok }.forEach { check ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                MIcon(MIcons.alert, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(check.title, style = MillidaType.body, color = colors.fg)
                    if (check.detail.isNotEmpty()) {
                        Text(check.detail, style = MillidaType.small, color = colors.fgFaint)
                    }
                }
            }
        }
        if (diagnosis.fixable) {
            Spacer(Modifier.height(12.dp))
            MPrimaryButton(
                "Починить",
                onClick = { model.repairCosmetics(profile) },
                enabled = !busy,
                icon = MIcons.shield,
                size = MButtonSize.Small,
            )
        }
    }
}

@Composable
private fun UpdatePlanCard(profile: Profile, plan: UpdatePlan, busy: Boolean, model: LauncherViewModel) {
    val colors = millida
    MCard {
        if (plan.empty) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MIcon(MIcons.check, size = 18.dp, color = colors.accentText)
                Spacer(Modifier.width(10.dp))
                Text("Всё свежее", style = MillidaType.rowTitle, color = colors.fg)
            }
            return@MCard
        }
        Text("Обновлений: ${plan.candidates.size}", style = MillidaType.rowTitle, color = colors.fg)
        Spacer(Modifier.height(6.dp))
        plan.candidates.take(PLAN_PREVIEW).forEach { candidate ->
            Text(
                "${candidate.title}: ${candidate.fromVersion} → ${candidate.toVersion}",
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (plan.candidates.size > PLAN_PREVIEW) {
            Text("и ещё ${plan.candidates.size - PLAN_PREVIEW}", style = MillidaType.small, color = colors.fgFaint)
        }
        Spacer(Modifier.height(12.dp))
        MPrimaryButton(
            "Обновить всё",
            onClick = { model.applyContentUpdates(profile) },
            enabled = !busy,
            icon = MIcons.download,
            size = MButtonSize.Small,
        )
    }
}

@Composable
private fun SafetyCard(profile: Profile, report: SafetyReport, busy: Boolean, model: LauncherViewModel) {
    val colors = millida
    val flagged = report.items.filter { it.verdict != SafetyVerdict.OK }
    MCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Проверено: ${report.checked}",
                modifier = Modifier.weight(1f),
                style = MillidaType.rowTitle,
                color = colors.fg,
            )
            if (report.problems > 0) {
                MBadge("С вопросами: ${report.problems}", colors.warning, colors.warningSoft)
            } else {
                MBadge("Всё в порядке", colors.accentText, colors.accentSoft)
            }
        }
        flagged.take(SAFETY_PREVIEW).forEach { verdict ->
            Spacer(Modifier.height(4.dp))
            Text(
                "${verdict.file.title.ifEmpty { verdict.file.name }} — ${verdict.verdict.label}",
                style = MillidaType.small,
                color = if (verdict.quarantinable) colors.warning else colors.fgSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (report.toQuarantine().isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            MSecondaryButton(
                "В карантин",
                onClick = { model.quarantineMods(profile) },
                enabled = !busy,
                icon = MIcons.shield,
                size = MButtonSize.Small,
            )
        }
    }
}
