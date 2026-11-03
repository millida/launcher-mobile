package net.millida.launcher.remoteui

import androidx.webkit.JavaScriptReplyProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.core.remoteui.RemoteUiBridgeRules
import java.io.IOException

/**
 * The only door between a website screen and the app (LauncherBridge v1 of millida-web). The screen sends
 * `{id, op, args}` through one message channel and gets `{id, ok, data | error}` back: a Promise on the JS
 * side. The token stays in the core: `api` goes through the app's own authorised client. Nothing is
 * handed out that the desktop launcher does not hand out; unknown operations are ignored.
 */
class RemoteBridge(
    private val model: LauncherViewModel,
    private val scope: CoroutineScope,
    private val onReady: () -> Unit,
    private val onFail: (crash: Boolean, why: String) -> Unit,
) {
    fun handle(text: String, reply: JavaScriptReplyProxy) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val op = message["op"]?.jsonPrimitive?.contentOrNull ?: return
        val id = message["id"]?.jsonPrimitive?.intOrNull
        val args = (message["args"] as? JsonObject) ?: JsonObject(emptyMap())
        when (op) {
            "ready" -> onReady()
            "fail" -> onFail(false, args.str("why"))
            "crash" -> onFail(true, args.str("why"))
            "navigate" -> model.navigateFromRemote(args.str("path"))
            "openExternal" -> model.openExternalFromRemote(args.str("url"))
            "toast" -> model.toastFromRemote(args.str("message").take(300), args.str("kind") == "error")
            "api", "install", "join", "profiles" -> {
                if (id == null) return
                scope.launch { reply.postMessage(answer(id, op, args)) }
            }
        }
    }

    private suspend fun answer(id: Int, op: String, args: JsonObject): String = try {
        ok(
            id,
            when (op) {
                "api" -> api(args)
                "install" -> install(args)
                "join" -> join(args)
                else -> profiles()
            },
        )
    } catch (failed: ApiFailure) {
        error(id, failed.status, failed.body, failed.message ?: "")
    } catch (offline: IOException) {
        error(id, 0, JsonNull, offline.message ?: "нет связи")
    } catch (broken: Exception) {
        error(id, 500, JsonNull, broken.message ?: "ошибка")
    }

    private suspend fun api(args: JsonObject): JsonElement {
        val path = args.str("path")
        val method = args.str("method").ifEmpty { "GET" }.uppercase()
        if (!RemoteUiBridgeRules.apiAllowed(path, method)) throw ApiFailure(403, JsonNull, "not allowed for website screens")
        val body = args["body"]?.takeIf { it !is JsonNull }?.toString()
        val raw = model.remoteApi(path, method, body)
        val parsed = if (raw.body.isBlank()) JsonNull else runCatching { Json.parseToJsonElement(raw.body) }.getOrDefault(JsonNull)
        if (raw.status !in 200..299) {
            val message = ((parsed as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            throw ApiFailure(raw.status, parsed, message ?: "http ${raw.status}")
        }
        return parsed
    }

    private fun install(args: JsonObject): JsonElement {
        val item = args["item"] as? JsonObject ?: throw ApiFailure(400, JsonNull, "no item")
        val target = args["target"] as? JsonObject
        if (target?.str("kind") == "server") throw ApiFailure(400, JsonNull, "server installs go through the hosting API")
        val slug = item.str("slug")
        val section = item.str("section")
        if (slug.isEmpty() || section.isEmpty()) throw ApiFailure(400, JsonNull, "no item")
        model.installFromRemote(
            section = section,
            slug = slug,
            name = item.str("name").ifEmpty { null },
            source = item.str("source").ifEmpty { null },
            cfid = (item["cfid"] as? JsonPrimitive)?.intOrNull,
            build = if (target?.str("kind") == "build") target.str("id").ifEmpty { null } else null,
        )
        return JsonNull
    }

    private fun join(args: JsonObject): JsonElement {
        val server = args["server"] as? JsonObject ?: throw ApiFailure(400, JsonNull, "no server")
        val address = server.str("address")
        if (address.isBlank()) throw ApiFailure(400, JsonNull, "no address")
        model.joinFromRemote(address, server.str("name").ifEmpty { address }, server.str("version").ifEmpty { null })
        return JsonNull
    }

    private fun profiles(): JsonElement {
        val (list, selected) = model.remoteProfiles()
        return buildJsonObject {
            put(
                "list",
                JsonArray(
                    list.map {
                        buildJsonObject {
                            put("name", it.name)
                            put("version", it.gameVersion)
                            if (it.loader.id == "vanilla") put("loader", JsonNull) else put("loader", it.loader.id)
                        }
                    },
                ),
            )
            if (selected == null) put("selected", JsonNull) else put("selected", selected)
        }
    }

    private class ApiFailure(val status: Int, val body: JsonElement, message: String) : Exception(message)

    private fun ok(id: Int, data: JsonElement): String =
        buildJsonObject {
            put("id", id)
            put("ok", true)
            put("data", data)
        }.toString()

    private fun error(id: Int, status: Int, body: JsonElement, message: String): String = buildJsonObject {
        put("id", id)
        put("ok", false)
        putJsonObject("error") {
            put("status", status)
            put("body", body)
            put("message", message)
        }
    }.toString()

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
