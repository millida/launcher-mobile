package net.millida.launcher.ui.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.Container
import net.millida.launcher.core.catalog.AiBuilder
import net.millida.launcher.core.catalog.AiBuilderClient
import net.millida.launcher.core.catalog.AiInstall
import net.millida.launcher.core.catalog.AiMod
import net.millida.launcher.core.catalog.AiPlan
import net.millida.launcher.core.catalog.AiQuota
import net.millida.launcher.core.catalog.CatalogItem
import net.millida.launcher.core.catalog.CatalogSource
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.content.InstalledIndex
import net.millida.launcher.core.packs.PackProgress
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.telemetry.EventData
import net.millida.launcher.core.telemetry.TelemetryType
import java.io.File
import java.io.IOException

data class AiBuilderState(
    val open: Boolean = false,
    val prompt: String = "",
    val quota: AiQuota? = null,
    val building: Boolean = false,
    val error: String? = null,
    val plan: AiPlan? = null,
    val title: String = "",
    val off: Set<String> = emptySet(),
) {
    val ready: Boolean get() = prompt.trim().length >= AiBuilder.PROMPT_MIN

    val soon: Boolean get() = quota?.enabled == false

    val chosen: AiInstall? get() = plan?.let { AiBuilder.install(it, off) }
}

/**
 * The AI pack builder: words in, a plan of real Modrinth projects out, and
 * «Создать сборку» makes a build for the plan's version and loader and puts
 * every chosen project in with its required dependencies.
 */
class AiBuilderModel(
    private val container: Container,
    private val scope: CoroutineScope,
    private val catalog: CatalogModel,
    private val host: MillidaShelfHost,
) {
    private val mutable = MutableStateFlow(AiBuilderState())
    val state: StateFlow<AiBuilderState> = mutable.asStateFlow()

    private val client = AiBuilderClient(container.millidaAuth)
    private var cachedKey = ""

    fun open() {
        mutable.update { it.copy(open = true) }
        loadQuota()
    }

    fun close() = mutable.update { it.copy(open = false) }

    fun setPrompt(prompt: String) = mutable.update { it.copy(prompt = prompt.take(AiBuilder.PROMPT_MAX)) }

    fun setTitle(title: String) = mutable.update { it.copy(title = title.take(AiBuilder.TITLE_MAX)) }

    fun toggle(mod: AiMod) {
        if (mod.slug in AiBuilder.LOCKED_SLUGS) return
        mutable.update { state ->
            state.copy(off = if (mod.projectId in state.off) state.off - mod.projectId else state.off + mod.projectId)
        }
    }

    fun reset() = mutable.update { it.copy(plan = null, error = null, off = emptySet(), title = "") }

    /** Signing in changes whose quota is shown. */
    fun loadQuota() {
        if (!host.signedIn) {
            mutable.update { it.copy(quota = null) }
            return
        }
        scope.launch {
            val quota = try {
                withContext(Dispatchers.IO) { client.quota() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            mutable.update { it.copy(quota = quota) }
        }
    }

    fun build() {
        val current = mutable.value
        if (!current.ready || current.building || current.soon) return
        if (!host.signedIn) {
            host.notice("Войди в аккаунт Millida — ИИ-сборщик работает в нём", true)
            host.signIn()
            return
        }
        val key = AiBuilder.requestKey(current.prompt)
        mutable.update { it.copy(building = true, error = null, plan = null) }
        scope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { client.build(current.prompt, fresh = cachedKey == key) }
                cachedKey = if (plan.cached) key else ""
                mutable.update {
                    it.copy(
                        building = false,
                        plan = plan,
                        title = plan.title.take(AiBuilder.TITLE_MAX),
                        off = emptySet(),
                        quota = AiQuota(enabled = true, limit = plan.limit, remaining = plan.remaining, plus = it.quota?.plus == true),
                    )
                }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(building = false) }
                throw cancelled
            } catch (error: Throwable) {
                mutable.update { it.copy(building = false, error = AiBuilder.errorText(error)) }
            }
        }
    }

    /** The build is created first, then each kind is installed in its own pass; one failed project does not stop the rest. */
    fun create() {
        val current = mutable.value
        val plan = current.plan ?: return
        val chosen = current.chosen ?: return
        if (chosen.empty) return
        val name = current.title.trim().ifEmpty { plan.title }.take(AiBuilder.TITLE_MAX)
        val failed = ArrayList<String>()
        catalog.runModpack(
            key = JOB_PREFIX + name,
            title = name,
            onInstalled = { _ ->
                container.telemetry.track(
                    TelemetryType.BUILD_CREATE,
                    EventData()
                        .put("mc", plan.mcVersion)
                        .put("loader", plan.loader.id)
                        .put("from", "ai")
                        .put("mods", chosen.mods.size)
                        .put("resourcepacks", chosen.resourcepacks.size)
                        .put("shaders", chosen.shaders.size)
                        .build(),
                )
                mutable.update { AiBuilderState(quota = it.quota) }
                if (failed.isEmpty()) {
                    host.notice("Сборка «$name» готова", false)
                } else {
                    host.notice("Не встало: ${failed.take(3).joinToString("; ")}", true)
                }
            },
        ) { onStep ->
            onStep(PackProgress("Создаём сборку"))
            val profile = container.packs.createBuild(name, plan.mcVersion, plan.loader, onStep)
            val dir = container.profiles.directory(profile)
            val total = chosen.mods.size + chosen.resourcepacks.size + chosen.shaders.size
            var done = 0
            val step: (AiMod) -> Unit = { mod ->
                done += 1
                onStep(PackProgress("Ставим моды", done, total, mod.title))
            }
            val modsIn = installAll(profile, dir, chosen.mods, ContentKind.MOD, failed, step)
            if (chosen.mods.isNotEmpty() && modsIn == 0) {
                throw IOException("ни один мод не поставился: ${failed.take(3).joinToString("; ")} — сборка «$name» осталась пустой")
            }
            installAll(profile, dir, chosen.resourcepacks, ContentKind.RESOURCE_PACK, failed, step)
            installAll(profile, dir, chosen.shaders, ContentKind.SHADER, failed, step)
            profile
        }
    }

    private suspend fun installAll(
        profile: Profile,
        dir: File,
        list: List<AiMod>,
        kind: ContentKind,
        failed: MutableList<String>,
        step: (AiMod) -> Unit,
    ): Int {
        var installed = 0
        val placed = HashSet<String>()
        list.forEach { mod ->
            try {
                val index = InstalledIndex.of(container.content.list(dir, kind))
                if (mod.projectId in placed || index.has(mod.projectId, mod.slug, mod.title)) {
                    installed += 1
                    return@forEach
                }
                val version = pickVersion(mod, profile, kind)
                container.catalog.install(version, kind, dir)
                placed += mod.projectId
                installed += 1
                if (kind == ContentKind.MOD) {
                    val plan = container.contentDependencies.plan(profile, dir, version, kind)
                    val report = container.contentDependencies.install(dir, plan.missing, kind)
                    plan.missing.forEach { placed += it.projectId }
                    report.failed.forEach { (title, reason) -> failed += "$title: $reason" }
                    plan.unresolved.forEach { failed += "${it.title}: ${it.problem}" }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed += "${mod.title}: ${error.message ?: "не скачался"}"
            } finally {
                step(mod)
            }
        }
        return installed
    }

    @Throws(IOException::class)
    private suspend fun pickVersion(mod: AiMod, profile: Profile, kind: ContentKind): CatalogVersion {
        val item = CatalogItem(projectId = mod.projectId, slug = mod.slug, title = mod.title, source = CatalogSource.MODRINTH)
        val loader = if (kind == ContentKind.MOD) profile.loader else null
        val versions = container.catalog.versionsFor(item, profile.gameVersion, loader).filter { it.primaryFile() != null }
        return versions.firstOrNull { it.versionType == "release" } ?: versions.firstOrNull()
            ?: throw IOException("нет версии под ${profile.gameVersion}")
    }

    companion object {
        const val JOB_PREFIX = "ai:"
    }
}
