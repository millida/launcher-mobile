package net.millida.launcher.ui.catalog

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.millida.launcher.core.catalog.CatalogItem

data class StarEntry(val tab: String, val item: CatalogItem, val at: Long = 0L) {
    val catalogTab: CatalogTab? get() = CatalogTab.entries.firstOrNull { it.name == tab }
}

enum class StarResult { ON, OFF, FULL, FAILED }

/**
 * The desktop's catalogue favourites: a list kept on this device only, the same
 * way the desktop keeps it in the launcher's local storage.
 */
class CatalogStars private constructor(private val prefs: SharedPreferences) {
    private val mutable = MutableStateFlow(read())
    val items: StateFlow<List<StarEntry>> = mutable.asStateFlow()

    fun starred(tab: CatalogTab, item: CatalogItem): Boolean =
        mutable.value.any { it.tab == tab.name && it.item.key == item.key }

    @Synchronized
    fun toggle(tab: CatalogTab, item: CatalogItem): StarResult {
        val current = mutable.value
        val had = current.any { it.tab == tab.name && it.item.key == item.key }
        if (!had && current.size >= STAR_LIMIT) return StarResult.FULL
        val next = if (had) {
            current.filterNot { it.tab == tab.name && it.item.key == item.key }
        } else {
            listOf(StarEntry(tab.name, item, System.currentTimeMillis())) + current
        }
        if (!write(next)) return StarResult.FAILED
        mutable.value = next
        return if (had) StarResult.OFF else StarResult.ON
    }

    private fun read(): List<StarEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.parseToJsonElement(raw).jsonArray.mapNotNull(::entryOf) }
            .getOrDefault(emptyList())
            .filter { it.catalogTab != null && it.item.projectId.isNotBlank() && it.item.title.isNotBlank() }
            .distinctBy { it.tab + "/" + it.item.key }
            .take(STAR_LIMIT)
    }

    private fun write(list: List<StarEntry>): Boolean =
        runCatching {
            val array = buildJsonArray {
                list.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("tab", entry.tab)
                            put("at", entry.at)
                            put("item", json.encodeToJsonElement(CatalogItem.serializer(), entry.item))
                        },
                    )
                }
            }
            prefs.edit().putString(KEY, array.toString()).commit()
        }.getOrDefault(false)

    private fun entryOf(raw: JsonElement): StarEntry? {
        val obj = raw as? JsonObject ?: return null
        val tab = (obj["tab"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val item = obj["item"]?.let { runCatching { json.decodeFromJsonElement(CatalogItem.serializer(), it) }.getOrNull() } ?: return null
        val at = obj["at"]?.jsonPrimitive?.longOrNull ?: 0L
        return StarEntry(tab, item, at)
    }

    companion object {
        const val STAR_LIMIT = 300
        private const val PREFS = "millida-catalog-stars"
        private const val KEY = "stars"
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: CatalogStars? = null

        fun of(context: Context): CatalogStars = instance ?: synchronized(this) {
            instance ?: CatalogStars(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
