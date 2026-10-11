package net.millida.launcher.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.Area
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.packs.MyPack
import net.millida.launcher.core.packs.PackCodes
import net.millida.launcher.core.packs.SharedPackView
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.shareFile
import java.io.File

private const val MRPACK_MIME = "application/x-modrinth-modpack+zip"

@Composable
fun PacksScreen(state: UiState, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val idle = state.busy == null && !importing

    var code by remember { mutableStateOf("") }
    var shareProfileId by remember { mutableStateOf(state.activeProfileId.orEmpty()) }
    var exporting by remember { mutableStateOf(false) }
    var readyExport by remember { mutableStateOf<File?>(null) }
    var seenSignedIn by remember { mutableStateOf(state.millidaSignedIn) }
    var revoking by remember { mutableStateOf<MyPack?>(null) }

    LaunchedEffect(state.millidaSignedIn) {
        if (state.millidaSignedIn && !seenSignedIn) model.loadMyPacks()
        seenSignedIn = state.millidaSignedIn
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val target = File(context.cacheDir, "import-${System.currentTimeMillis()}.mrpack")
            importing = true
            val ready = try {
                withContext(Dispatchers.IO) { copyToCache(context, uri, target) }
            } catch (cancelled: CancellationException) {
                target.delete()
                throw cancelled
            } finally {
                importing = false
            }
            if (ready) {
                model.installPackFile(target).invokeOnCompletion { target.delete() }
            } else {
                model.notice("Файл не прочитался — выбери .mrpack или zip со сборкой", isError = true)
            }
        }
    }
    val copyCode: (String) -> Unit = { shared ->
        clipboard.setText(AnnotatedString(shared))
        model.notice("Код скопирован: $shared")
    }
    val find: () -> Unit = { if (PackCodes.valid(code) && idle) model.previewPack(code) }
    val picked = state.profiles.firstOrNull { it.id == shareProfileId } ?: state.profiles.firstOrNull()

    MScreen(modifier = modifier, title = "Коды сборок", onBack = { model.back() }) {
        if (importing && state.busy == null) {
            item(key = "importing") { MBusyCard(label = "Читаем файл сборки", progress = null) }
        }
        state.busy?.let { task ->
            item(key = "busy") {
                MBusyCard(
                    label = task,
                    progress = state.progress.takeIf { it > 0f },
                    note = state.progressNote.ifEmpty { null },
                )
            }
        }

        item(key = "code-title") { MSectionHeader("Поставить по коду") }
        item(key = "code") {
            MCard {
                MField(
                    value = code,
                    onValueChange = { code = PackCodes.normalize(it) },
                    placeholder = "Код, например K7M2QRT9",
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    onImeAction = find,
                    leadingIcon = MIcons.key,
                    clearable = true,
                )
                Spacer(Modifier.height(12.dp))
                MPrimaryButton(
                    "Найти",
                    onClick = find,
                    enabled = PackCodes.valid(code) && idle,
                    icon = MIcons.search,
                )
            }
        }
        state.packPreview?.let { preview ->
            item(key = "preview-${preview.code}") {
                PackPreviewCard(preview, enabled = idle) { model.installPackByCode(preview.code) }
            }
        }
        item(key = "file") {
            MListRow(
                title = "Поставить из файла",
                subtitle = ".mrpack или zip со сборкой",
                icon = MIcons.folder,
                onClick = { if (idle) pickFile.launch("*/*") },
            )
        }

        item(key = "mine-title") {
            PacksShelfTitle("Мои коды") {
                if (state.millidaSignedIn) {
                    MIconButton(
                        MIcons.refresh,
                        onClick = { model.loadMyPacks() },
                        label = "Обновить",
                        enabled = !state.isLoading(Area.PACKS),
                    )
                }
            }
        }
        val packsError = state.errorOf(Area.PACKS)
        when {
            !state.millidaSignedIn -> item(key = "mine-gate") {
                MSignInGate(onSignIn = { model.loginMillida() }, title = "Войди, чтобы видеть коды")
            }
            packsError != null -> item(key = "mine-error") {
                MErrorCard(packsError, onRetry = { model.retry(Area.PACKS) })
            }
            state.myPacks.isEmpty() && state.isLoading(Area.PACKS) -> items(2, key = { "mine-skeleton-$it" }) {
                MSkeletonRow()
            }
            state.myPacks.isEmpty() -> item(key = "mine-empty") {
                MEmptyState(
                    icon = MIcons.key,
                    title = "Кодов пока нет",
                    actionText = if (picked != null) "Получить код" else null,
                    onAction = { picked?.let { profile -> model.sharePack(profile, onDone = copyCode) } },
                    actionIcon = MIcons.copy,
                    primary = false,
                )
            }
        }
        if (state.millidaSignedIn) {
            items(state.myPacks, key = { "pack-${it.code}" }) { pack ->
                MyPackRow(pack, Modifier.animateItem(), onRevoke = { revoking = pack }) { copyCode(pack.code) }
            }
        }

        item(key = "share-title") { MSectionHeader("Поделиться сборкой") }
        if (picked == null) {
            item(key = "share-empty") {
                MEmptyState(
                    icon = MIcons.box,
                    title = "Создай сборку во вкладке «Играть»",
                    actionText = "Играть",
                    onAction = { model.open(Screen.HOME) },
                    actionIcon = MIcons.play,
                )
            }
        } else {
            item(key = "share") {
                MCard {
                    Text("СБОРКА", style = MillidaType.caption, color = millida.fgSubtle)
                    Spacer(Modifier.height(8.dp))
                    ProfilePicker(state.profiles, picked) { shareProfileId = it.id }
                    Spacer(Modifier.height(14.dp))
                    MButtonRow {
                        if (state.millidaSignedIn) {
                            MPrimaryButton(
                                "Получить код",
                                onClick = { model.sharePack(picked, onDone = copyCode) },
                                enabled = idle,
                                icon = MIcons.copy,
                            )
                        } else {
                            MPrimaryButton("Войти за кодом", onClick = { model.loginMillida() }, icon = MIcons.login)
                        }
                        MSecondaryButton(
                            "Файл .mrpack",
                            onClick = {
                                exporting = true
                                val stem = SafePath.fileName(picked.name.ifEmpty { "millida-pack" })
                                val target = File(context.cacheDir, "$stem.mrpack")
                                model.exportPack(picked, target) { file -> readyExport = file }
                                    .invokeOnCompletion { exporting = false }
                            },
                            enabled = idle && !exporting,
                            icon = MIcons.download,
                            loading = exporting,
                        )
                    }
                }
            }
        }
    }

    revoking?.let { pack ->
        MConfirmDialog(
            title = "Удалить код ${pack.code}?",
            text = "По нему сборка «${pack.name.ifEmpty { pack.code }}» больше не поставится",
            confirm = "Удалить",
            onConfirm = {
                revoking = null
                model.unsharePack(pack.code)
            },
            onDismiss = { revoking = null },
        )
    }

    readyExport?.let { file ->
        MDialog(
            title = "Файл готов",
            onDismiss = { readyExport = null },
            body = {
                Text(
                    "${file.name} · ${humanSize(file.length())}",
                    style = MillidaType.body,
                    color = millida.fgMuted,
                )
            },
            actions = {
                MTextButton("Позже", onClick = { readyExport = null })
                MPrimaryButton(
                    "Отправить",
                    onClick = {
                        val sent = shareFile(context, file, MRPACK_MIME, "Сборка Millida")
                        if (!sent) model.notice("Не нашлось приложения, готового принять файл", isError = true)
                        readyExport = null
                    },
                    icon = MIcons.share,
                )
            },
        )
    }
}

@Composable
private fun PacksShelfTitle(title: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MillidaType.sectionTitle,
            color = millida.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackPreviewCard(preview: SharedPackView, enabled: Boolean, onInstall: () -> Unit) {
    val colors = millida
    MCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    preview.name.ifEmpty { preview.code },
                    style = MillidaType.rowTitle.copy(fontSize = MillidaType.sectionTitle.fontSize),
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.author.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Собрал ${preview.author}",
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            MBadge(preview.code, colors.accentText, colors.accentSoft)
        }
        if (preview.summary.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                preview.summary,
                style = MillidaType.body,
                color = colors.fgMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            previewFacts(preview).forEach { (value, caption) -> PackFact(value, caption) }
        }
        Spacer(Modifier.height(14.dp))
        MPrimaryButton("Установить", onClick = onInstall, enabled = enabled, icon = MIcons.download)
    }
}

@Composable
private fun PackFact(value: String, caption: String) {
    val colors = millida
    Column(
        Modifier
            .pixelSurface(colors.inset, MillidaCut.control, colors.wellFrame())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(value, style = MillidaType.pixel, color = colors.fg, maxLines = 1)
        Text(caption, style = MillidaType.small, color = colors.fgFaint, maxLines = 1)
    }
}

@Composable
private fun MyPackRow(pack: MyPack, modifier: Modifier = Modifier, onRevoke: () -> Unit, onCopy: () -> Unit) {
    val colors = millida
    MListRow(
        title = pack.name.ifEmpty { pack.code },
        modifier = modifier,
        subtitle = myPackFacts(pack),
        trailing = {
            MBadge(pack.code, colors.accentText, colors.accentSoft)
            MIconButton(MIcons.copy, onClick = onCopy, label = "Скопировать код")
            MIconButton(MIcons.trash, onClick = onRevoke, tint = colors.danger, label = "Удалить код")
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePicker(profiles: List<Profile>, picked: Profile, onPick: (Profile) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        profiles.forEach { profile ->
            MChip(
                profile.name.ifEmpty { profile.label },
                selected = profile.id == picked.id,
                onClick = { onPick(profile) },
                icon = MIcons.box,
            )
        }
    }
}

private fun previewFacts(preview: SharedPackView): List<Pair<String, String>> = listOfNotNull(
    preview.gameVersion.takeIf { it.isNotEmpty() }?.let { it to "версия" },
    preview.loader.takeIf { it.isNotEmpty() }?.let { it to "загрузчик" },
    preview.files.toString() to "файлов",
    preview.sizeBytes.takeIf { it > 0 }?.let { humanSize(it) to "размер" },
)

private fun myPackFacts(pack: MyPack): String = listOf(
    "${pack.files} файлов",
    "ставили ${pack.installs} раз",
).joinToString(" · ")

/**
 * The installer works on a real file, and a content Uri is not one: the picked
 * bytes are copied first so a provider that dies mid-read cannot leave a
 * half-read archive looking complete.
 */
private fun copyToCache(context: android.content.Context, uri: Uri, target: File): Boolean {
    val copied = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching 0L
    }.getOrNull() ?: 0L
    if (copied <= 0L) {
        target.delete()
        return false
    }
    return true
}
