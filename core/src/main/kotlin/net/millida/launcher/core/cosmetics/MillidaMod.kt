package net.millida.launcher.core.cosmetics

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.Hashing
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.DISABLED_SUFFIX
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.ProfileStore
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

const val MILLIDA_MOD_MANIFEST_URL = "https://cdn.millida.net/mod/variants.json"
const val MILLIDA_MOD_BASE_URL = "https://cdn.millida.net/mod"
const val MILLIDA_MOD_FILE_PREFIX = "millida-mod-"

data class CosmeticsVariant(
    val id: String,
    val loaders: List<String>,
    val from: String,
    val to: String?,
    val artifact: String,
    val javaMajor: Int = 0,
    val sha256: String? = null,
    val size: Long = 0,
)

data class CosmeticsManifest(
    val version: String,
    val baseUrl: String,
    val variants: List<CosmeticsVariant>,
)

enum class CosmeticsModStatus {
    INSTALLED,
    OUTDATED,
    DISABLED,
    MISSING,
    UNSUPPORTED_LOADER,
    UNSUPPORTED_VERSION,
    CATALOG_INCOMPLETE,
}

data class CosmeticsModState(
    val profileId: String,
    val gameVersion: String,
    val loaderId: String,
    val status: CosmeticsModStatus,
    val installedFile: String = "",
    val installedEnabled: Boolean = true,
    val availableFile: String = "",
    val variantId: String = "",
    val modVersion: String = "",
    val reason: String = "",
) {
    val installed: Boolean get() = installedFile.isNotEmpty()
    val ready: Boolean get() = status == CosmeticsModStatus.INSTALLED
    val canInstall: Boolean get() = availableFile.isNotEmpty()
}

enum class SkinFix { NONE, INSTALL, ENABLE }

data class SkinCheck(
    val id: String,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val fix: SkinFix = SkinFix.NONE,
) {
    val fixable: Boolean get() = fix != SkinFix.NONE
}

data class SkinDiagnosis(
    val profileId: String,
    val profileName: String,
    val state: CosmeticsModState,
    val checks: List<SkinCheck>,
    val summary: String,
) {
    val ok: Boolean get() = checks.all { it.ok }
    val fix: SkinFix get() = checks.firstOrNull { !it.ok }?.fix ?: SkinFix.NONE
    val fixable: Boolean get() = fix != SkinFix.NONE
}

/**
 * Rules of the desktop launcher, kept identical on purpose: a build must get the
 * same jar on both, or the same player sees cosmetics on one device only.
 */
object CosmeticsCatalog {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    @Throws(IOException::class)
    fun parse(body: String): CosmeticsManifest {
        val root = runCatching { lenient.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IOException("Каталог версий мода повреждён — попробуй позже") }
        val variants = root["variants"]?.jsonArray.orEmpty()
            .mapNotNull { node -> runCatching { variant(node as? JsonObject) }.getOrNull() }
        if (variants.isEmpty()) throw IOException("Каталог версий мода пуст — попробуй позже")
        return CosmeticsManifest(
            version = root.str("version"),
            baseUrl = root.str("baseUrl").ifEmpty { MILLIDA_MOD_BASE_URL },
            variants = variants,
        )
    }

    private fun variant(node: JsonObject?): CosmeticsVariant? {
        if (node == null) return null
        val artifact = node.str("artifact")
        if (artifact.isEmpty()) return null
        val loaders = node["loaders"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        if (loaders.isEmpty()) return null
        val range = node["minecraft"] as? JsonObject
        return CosmeticsVariant(
            id = node.str("id").ifEmpty { artifact },
            loaders = loaders,
            from = range?.str("from").orEmpty(),
            to = range?.str("to")?.ifEmpty { null },
            artifact = artifact,
            javaMajor = node["javaMajor"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            sha256 = node.str("sha256").ifEmpty { null },
            size = node["size"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    /**
     * Numbers, not text: 1.21.10 is newer than 1.21.9 even though the string is
     * smaller. The year numbering (26.1) lands above the old branch by the same
     * rule, which is what keeps a 26.x build off a 1.21 variant.
     */
    internal fun versionKey(version: String): List<Long> =
        version.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }

    internal fun compare(left: String, right: String): Int {
        val a = versionKey(left)
        val b = versionKey(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** Range closed on the left, open on the right: the upper bound belongs to the next variant. */
    fun fits(variant: CosmeticsVariant, gameVersion: String, loaderId: String): Boolean {
        if (loaderId !in variant.loaders) return false
        if (variant.from.isNotEmpty() && compare(gameVersion, variant.from) < 0) return false
        val to = variant.to
        return to.isNullOrEmpty() || compare(gameVersion, to) < 0
    }

    fun pick(manifest: CosmeticsManifest, gameVersion: String, loaderId: String): CosmeticsVariant? =
        manifest.variants.firstOrNull { fits(it, gameVersion, loaderId) }

    /** Null when the catalogue names a version placeholder it never filled in. */
    fun artifactName(manifest: CosmeticsManifest, variant: CosmeticsVariant): String? {
        if (!variant.artifact.contains(VERSION_PLACEHOLDER)) return variant.artifact
        if (manifest.version.isEmpty()) return null
        return variant.artifact.replace(VERSION_PLACEHOLDER, manifest.version)
    }

    fun downloadUrl(manifest: CosmeticsManifest, artifact: String): String =
        manifest.baseUrl.trimEnd('/') + "/" + artifact

    fun isModFile(name: String): Boolean =
        name.startsWith(MILLIDA_MOD_FILE_PREFIX) &&
            (name.endsWith(".jar") || name.endsWith(".jar$DISABLED_SUFFIX"))

    fun enabledName(name: String): String = name.removeSuffix(DISABLED_SUFFIX)

    fun evaluate(
        profileId: String,
        gameVersion: String,
        loaderId: String,
        manifest: CosmeticsManifest,
        jars: List<String>,
    ): CosmeticsModState {
        val present = jars.filter { isModFile(it) }.sorted()
        val installedFile = present.firstOrNull { !it.endsWith(DISABLED_SUFFIX) } ?: present.firstOrNull().orEmpty()
        val enabled = installedFile.isNotEmpty() && !installedFile.endsWith(DISABLED_SUFFIX)
        val base = CosmeticsModState(
            profileId = profileId,
            gameVersion = gameVersion,
            loaderId = loaderId,
            status = CosmeticsModStatus.MISSING,
            installedFile = installedFile,
            installedEnabled = enabled,
        )

        if (loaderId == LoaderKind.VANILLA.id) {
            return base.copy(
                status = CosmeticsModStatus.UNSUPPORTED_LOADER,
                reason = "Косметика ставится только в сборку с загрузчиком: Fabric или Quilt",
            )
        }

        val variant = pick(manifest, gameVersion, loaderId)
            ?: return base.copy(
                status = CosmeticsModStatus.UNSUPPORTED_VERSION,
                reason = "Под $loaderId $gameVersion косметики пока нет — выбери поддерживаемую версию игры",
            )
        val artifact = artifactName(manifest, variant)
            ?: return base.copy(
                status = CosmeticsModStatus.CATALOG_INCOMPLETE,
                variantId = variant.id,
                reason = "Каталог не сообщил версию мода — обнови лаунчер или попробуй позже",
            )

        val known = base.copy(
            availableFile = artifact,
            variantId = variant.id,
            modVersion = manifest.version,
        )
        return when {
            installedFile.isEmpty() -> known.copy(status = CosmeticsModStatus.MISSING)
            !enabled -> known.copy(status = CosmeticsModStatus.DISABLED)
            enabledName(installedFile) != artifact -> known.copy(status = CosmeticsModStatus.OUTDATED)
            else -> known.copy(status = CosmeticsModStatus.INSTALLED)
        }
    }

    fun diagnose(profileName: String, state: CosmeticsModState): SkinDiagnosis {
        val checks = mutableListOf<SkinCheck>()
        val loaderOk = state.status != CosmeticsModStatus.UNSUPPORTED_LOADER
        checks += SkinCheck(
            id = "loader",
            title = "Загрузчик сборки",
            detail = if (loaderOk) {
                "Сборка на ${state.loaderId} — косметика сюда ставится"
            } else {
                "Сборка ванильная, ставить мод некуда. Создай сборку с Fabric или Quilt и играй в ней"
            },
            ok = loaderOk,
        )
        if (loaderOk) {
            val coverageOk = state.status != CosmeticsModStatus.UNSUPPORTED_VERSION &&
                state.status != CosmeticsModStatus.CATALOG_INCOMPLETE
            checks += SkinCheck(
                id = "coverage",
                title = "Версия игры",
                detail = if (coverageOk) {
                    "Для ${state.gameVersion} косметика собрана"
                } else {
                    state.reason.ifEmpty { "Под эту версию игры косметики пока нет" }
                },
                ok = coverageOk,
            )
            if (coverageOk) {
                checks += SkinCheck(
                    id = "present",
                    title = "Мод в сборке",
                    detail = if (state.installed) {
                        "Файл ${state.installedFile} лежит в папке модов"
                    } else {
                        "Мода нет в сборке. Нажми «Починить» — лаунчер поставит косметику"
                    },
                    ok = state.installed,
                    fix = if (state.installed) SkinFix.NONE else SkinFix.INSTALL,
                )
                if (state.installed) {
                    checks += SkinCheck(
                        id = "enabled",
                        title = "Мод включён",
                        detail = if (state.installedEnabled) {
                            "Мод включён и загрузится вместе с игрой"
                        } else {
                            "Мод выключен: файл помечен как $DISABLED_SUFFIX. Нажми «Починить» и перезапусти сборку"
                        },
                        ok = state.installedEnabled,
                        fix = if (state.installedEnabled) SkinFix.NONE else SkinFix.ENABLE,
                    )
                    val fresh = state.status != CosmeticsModStatus.OUTDATED
                    checks += SkinCheck(
                        id = "version",
                        title = "Версия мода",
                        detail = if (fresh) {
                            "Стоит актуальная версия"
                        } else {
                            "В сборке ${state.installedFile}, а нужна ${state.availableFile}. " +
                                "Нажми «Починить» — лаунчер заменит файл"
                        },
                        ok = fresh,
                        fix = if (fresh) SkinFix.NONE else SkinFix.INSTALL,
                    )
                }
            }
        }
        val failed = checks.firstOrNull { !it.ok }
        return SkinDiagnosis(
            profileId = state.profileId,
            profileName = profileName,
            state = state,
            checks = checks,
            summary = failed?.detail
                ?: "Косметика на месте: скины, плащи, шляпы, крылья и эмоции работают. " +
                "Если скина всё равно нет, зайди на сервер заново и открой гардероб по клавише «M»",
        )
    }

    private const val VERSION_PLACEHOLDER = "{version}"
}

/**
 * Installs and repairs the cosmetics mod inside one build. The jar is the only
 * thing that draws skins, capes, hats, wings and emotes in game, so a failed
 * download must never leave the build without the copy it already had.
 */
class MillidaMod(
    private val layout: GameLayout,
    private val store: ProfileStore,
    private val downloader: Downloader,
    private val manifestUrl: String = MILLIDA_MOD_MANIFEST_URL,
) {
    private val gate = Mutex()
    private val attemptsLock = Any()

    @Volatile
    private var cached: CosmeticsManifest? = null

    @Throws(IOException::class)
    suspend fun manifest(refresh: Boolean = false): CosmeticsManifest {
        cached?.takeIf { !refresh }?.let { return it }
        return gate.withLock {
            cached?.takeIf { !refresh } ?: load().also { cached = it }
        }
    }

    private suspend fun load(): CosmeticsManifest {
        val body = try {
            downloader.text(manifestUrl)
        } catch (error: IOException) {
            throw IOException("Каталог версий косметики недоступен: ${error.message}", error)
        }
        return CosmeticsCatalog.parse(body)
    }

    @Throws(IOException::class)
    suspend fun state(profile: Profile): CosmeticsModState = state(profile, manifest())

    private fun state(profile: Profile, manifest: CosmeticsManifest): CosmeticsModState =
        CosmeticsCatalog.evaluate(
            profileId = profile.id,
            gameVersion = profile.gameVersion,
            loaderId = profile.loader.id,
            manifest = manifest,
            jars = jars(profile),
        )

    /**
     * A jar re-uploaded under the same name differs only in its digest, so a
     * matching name is not proof the build has the file the catalogue serves.
     */
    @Throws(IOException::class)
    suspend fun intact(profile: Profile, state: CosmeticsModState): Boolean {
        if (state.status != CosmeticsModStatus.INSTALLED) return true
        val sha = manifest().variants.firstOrNull { it.id == state.variantId }?.sha256 ?: return true
        return Hashing.matches(SafePath.join(mods(profile), state.installedFile), sha)
    }

    @Throws(IOException::class)
    suspend fun updateDue(profile: Profile, state: CosmeticsModState, now: Long = System.currentTimeMillis()): Boolean {
        val sha = targetSha(state)
        val last = synchronized(attemptsLock) { readAttempts().getProperty(profile.id) }?.split(' ')
        return MillidaModOffer.updateDue(last?.getOrNull(0), last?.getOrNull(1)?.toLongOrNull() ?: 0L, sha, now)
    }

    @Throws(IOException::class)
    suspend fun rememberUpdate(profile: Profile, now: Long = System.currentTimeMillis()) {
        val sha = targetSha(state(profile)) ?: return
        synchronized(attemptsLock) {
            val attempts = readAttempts()
            attempts.setProperty(profile.id, "$sha $now")
            val text = StringWriter().also { attempts.store(it, null) }.toString()
            attemptsFile().atomicWriteText(text)
        }
    }

    private suspend fun targetSha(state: CosmeticsModState): String? =
        manifest().variants.firstOrNull { it.id == state.variantId }?.sha256

    private fun attemptsFile(): File = File(layout.caches, ATTEMPTS_FILE)

    private fun readAttempts(): Properties {
        val attempts = Properties()
        val file = attemptsFile()
        if (file.isFile) runCatching { file.reader().use { attempts.load(it) } }
        return attempts
    }

    @Throws(IOException::class)
    suspend fun diagnose(profile: Profile): SkinDiagnosis =
        CosmeticsCatalog.diagnose(profile.name, state(profile))

    @Throws(IOException::class)
    suspend fun diagnose(profiles: List<Profile>): List<SkinDiagnosis> {
        val manifest = manifest()
        return profiles.map { CosmeticsCatalog.diagnose(it.name, state(it, manifest)) }
    }

    @Throws(IOException::class)
    suspend fun repair(profile: Profile, onChunk: (Long) -> Unit = {}): CosmeticsModState =
        when (diagnose(profile).fix) {
            SkinFix.INSTALL -> install(profile, onChunk)
            SkinFix.ENABLE -> setEnabled(profile, true)
            SkinFix.NONE -> state(profile)
        }

    @Throws(IOException::class)
    suspend fun install(profile: Profile, onChunk: (Long) -> Unit = {}): CosmeticsModState {
        val manifest = manifest()
        val current = state(profile, manifest)
        val artifact = current.availableFile
        if (artifact.isEmpty()) {
            throw IOException(current.reason.ifEmpty { "Косметика не подошла к этой сборке" })
        }
        val variant = manifest.variants.firstOrNull { it.id == current.variantId }
            ?: throw IOException("Вариант мода пропал из каталога — обнови список и попробуй снова")

        val mods = mods(profile)
        if (!mods.isDirectory && !mods.mkdirs()) {
            throw IOException("Не удалось создать папку модов сборки «${profile.name}»")
        }
        val target = SafePath.join(mods, artifact)
        val stale = jars(profile).filter { it != artifact }

        downloader.fetch(
            // Downloader picks the digest by its length, so a sha256 from the
            // catalogue is verified even though the field is named after sha1.
            RemoteFile(
                url = CosmeticsCatalog.downloadUrl(manifest, artifact),
                target = target,
                sha1 = variant.sha256,
                size = variant.size,
            ),
            onChunk,
        )
        if (!target.isFile) throw IOException("Файл косметики не появился в сборке — попробуй ещё раз")

        stale.forEach { File(mods, it).delete() }
        return state(profile, manifest)
    }

    @Throws(IOException::class)
    suspend fun remove(profile: Profile): CosmeticsModState {
        val mods = mods(profile)
        jars(profile).forEach { name ->
            val file = SafePath.join(mods, name)
            if (file.isFile && !file.delete()) {
                throw IOException("Не удалось убрать косметику из сборки «${profile.name}»")
            }
        }
        return state(profile)
    }

    @Throws(IOException::class)
    suspend fun setEnabled(profile: Profile, enabled: Boolean): CosmeticsModState {
        val mods = mods(profile)
        val name = jars(profile).firstOrNull()
            ?: throw IOException("Косметики нет в сборке «${profile.name}» — сначала установи её")
        val target = if (enabled) {
            CosmeticsCatalog.enabledName(name)
        } else {
            CosmeticsCatalog.enabledName(name) + DISABLED_SUFFIX
        }
        if (target != name) move(SafePath.join(mods, name), SafePath.join(mods, target))
        return state(profile)
    }

    private fun move(source: File, target: File) {
        if (target.exists()) throw IOException("В папке модов уже лежит файл ${target.name}")
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun mods(profile: Profile): File = File(store.directory(profile), MODS_FOLDER)

    private fun jars(profile: Profile): List<String> =
        mods(profile).list().orEmpty().filter { CosmeticsCatalog.isModFile(it) }.sorted()

    private companion object {
        const val MODS_FOLDER = "mods"
        const val ATTEMPTS_FILE = "millida-mod-updates.properties"
    }
}
