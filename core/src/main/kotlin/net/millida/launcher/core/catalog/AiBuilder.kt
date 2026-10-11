package net.millida.launcher.core.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.loader.LoaderKind
import java.io.IOException

data class AiQuota(val enabled: Boolean, val limit: Int, val remaining: Int, val plus: Boolean)

data class AiMod(
    val projectId: String,
    val slug: String,
    val title: String,
    val icon: String?,
    val why: String,
    /** A base mod by the server's rules: the loader's API, optimisation. */
    val base: Boolean,
)

data class AiExcluded(val slug: String, val title: String, val reason: String, val detail: String)

data class AiPlan(
    val title: String,
    val mcVersion: String,
    val loader: LoaderKind,
    val mods: List<AiMod>,
    val resourcepacks: List<AiMod> = emptyList(),
    val shaders: List<AiMod> = emptyList(),
    /** Iris or Oculus; installed only together with a chosen shader. */
    val shaderLoader: AiMod? = null,
    val notes: String = "",
    val limit: Int = 0,
    val remaining: Int = 0,
    val cached: Boolean = false,
    val excluded: List<AiExcluded> = emptyList(),
) {
    val all: List<AiMod> get() = mods + resourcepacks + shaders
}

/** What one press of «Создать сборку» installs, each kind in its own pass. */
data class AiInstall(val mods: List<AiMod>, val resourcepacks: List<AiMod>, val shaders: List<AiMod>) {
    val empty: Boolean get() = mods.isEmpty() && resourcepacks.isEmpty() && shaders.isEmpty()
}

/**
 * The AI pack builder: `POST /catalog/ai/build` answers with a plan of real
 * Modrinth projects. The server does not resolve dependencies; every mod is
 * installed with its required ones, as the desktop launcher does.
 */
class AiBuilderClient(private val auth: MillidaAuth) {

    @Throws(IOException::class)
    suspend fun quota(): AiQuota = AiBuilder.quotaFrom(auth.authorized("/catalog/ai/quota", "GET", null))

    @Throws(IOException::class)
    suspend fun build(prompt: String, fresh: Boolean): AiPlan {
        val text = prompt.trim().take(AiBuilder.PROMPT_MAX)
        if (text.length < AiBuilder.PROMPT_MIN) throw IOException("опиши сборку: от ${AiBuilder.PROMPT_MIN} до ${AiBuilder.PROMPT_MAX} символов")
        val body = auth.authorized(
            "/catalog/ai/build",
            "POST",
            buildJsonObject {
                put("prompt", text)
                if (fresh) put("fresh", true)
            },
        )
        return AiBuilder.planFrom(body) ?: throw IOException("ИИ-сборщик ответил не тем — попробуй ещё раз")
    }
}

object AiBuilder {
    const val PROMPT_MIN = 5
    const val PROMPT_MAX = 500
    const val TITLE_MAX = 24
    const val SOON = "ИИ-сборщик скоро заработает"
    const val LIMIT = "Лимит на сегодня — 3 сборки, в PLUS — 30"
    private const val NO_ANSWER = "ИИ не ответил — попробуй ещё раз"

    val EXAMPLES: List<String> = listOf("Хоррор с зомби на 1.20.1", "Техно и заводы", "Уютная ферма с едой", "Магия и данжи")

    /** Without them the loader's mods do not start, so they cannot be unticked. */
    val LOCKED_SLUGS: Set<String> = setOf("fabric-api", "qsl")

    private val LOADERS = mapOf(
        "fabric" to LoaderKind.FABRIC,
        "forge" to LoaderKind.FORGE,
        "neoforge" to LoaderKind.NEOFORGE,
        "quilt" to LoaderKind.QUILT,
    )

    private val EXCLUDED_WHY = mapOf(
        "quarantine" to "ломает запуск на этой версии",
        "wrong_mc" to "не для этой версии игры",
        "dep_version" to "не уживается с версией своей зависимости",
        "duplicate" to "повтор мода",
    )

    fun quotaFrom(body: JsonObject): AiQuota = AiQuota(
        enabled = body.flag("enabled") == true && body.flag("blocked") != true,
        limit = body.int("limit") ?: 0,
        remaining = body.int("remaining") ?: 0,
        plus = body.flag("plus") == true,
    )

    /** Null when the answer is not a plan: no version, an unknown loader or not a single mod. */
    fun planFrom(body: JsonObject): AiPlan? {
        val loader = LOADERS[body.text("loader").lowercase()] ?: return null
        val version = body.text("mcVersion").ifEmpty { return null }
        val mods = mods(body["mods"])
        if (mods.isEmpty()) return null
        return AiPlan(
            title = body.text("title").ifEmpty { "Сборка" },
            mcVersion = version,
            loader = loader,
            mods = mods,
            resourcepacks = mods(body["resourcepacks"]),
            shaders = mods(body["shaders"]),
            shaderLoader = (body["shaderLoader"] as? JsonObject)?.let(::mod),
            notes = body.text("notes"),
            limit = body.int("limit") ?: 0,
            remaining = body.int("remaining") ?: 0,
            cached = body.flag("cached") == true,
            excluded = (body["excluded"] as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                AiExcluded(row.text("slug"), row.text("title"), row.text("reason"), row.text("detail"))
            },
        )
    }

    /** Same text asked again means the player wants a new build, not the cached one. */
    fun requestKey(prompt: String): String = prompt.trim().lowercase()

    /**
     * The chosen part of the plan. Base mods go first, so the loader's API is
     * in place before the mods that require it; a chosen shader brings its
     * loader along, without which it does nothing.
     */
    fun install(plan: AiPlan, off: Set<String>): AiInstall {
        val keep: (List<AiMod>) -> List<AiMod> = { list -> list.filter { it.projectId !in off || it.slug in LOCKED_SLUGS } }
        val shaders = keep(plan.shaders)
        val chosenMods = keep(plan.mods)
        val loader = plan.shaderLoader
        val mods = if (shaders.isNotEmpty() && loader != null && chosenMods.none { it.projectId == loader.projectId }) {
            chosenMods + loader
        } else {
            chosenMods
        }
        return AiInstall(mods.sortedByDescending { it.base }, keep(plan.resourcepacks), shaders)
    }

    /** One line per mod the server left out, so a mod the player expected is not silently missing. */
    fun excludedLines(list: List<AiExcluded>): List<String> =
        list.map { it.title + " — " + (EXCLUDED_WHY[it.reason] ?: it.detail) }.distinct()

    /** The server already speaks Russian; only the bare statuses of the gateway are translated here. */
    fun errorText(error: Throwable): String {
        val raw = error.message.orEmpty().trim()
        return when {
            Regex("сервер ответил 503|^service unavailable$", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> SOON
            Regex("сервер ответил 429|^too many requests$|^throttlerexception", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> LIMIT
            Regex("сервер ответил 50[024]").containsMatchIn(raw) -> NO_ANSWER
            raw.isEmpty() -> NO_ANSWER
            else -> raw
        }
    }

    /** A limit or a closed builder is not fixed by asking again. */
    fun retryable(text: String): Boolean = !text.startsWith("Лимит") && text != SOON

    private fun mods(element: JsonElement?): List<AiMod> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::mod) }.distinctBy { it.projectId }

    private fun mod(row: JsonObject): AiMod? {
        val id = row.text("projectId").takeIf { PROJECT_ID.matches(it) } ?: return null
        return AiMod(
            projectId = id,
            slug = row.text("slug"),
            title = row.text("title").ifEmpty { row.text("slug").ifEmpty { id } },
            icon = row.text("icon").ifEmpty { null },
            why = row.text("why"),
            base = row.flag("base") == true,
        )
    }

    private val PROJECT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
