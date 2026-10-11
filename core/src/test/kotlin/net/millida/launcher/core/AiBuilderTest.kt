package net.millida.launcher.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.catalog.AiBuilder
import net.millida.launcher.core.catalog.AiExcluded
import net.millida.launcher.core.catalog.AiMod
import net.millida.launcher.core.catalog.AiPlan
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.mojang.MojangJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * The AI builder's plan is installed as is, so what is pinned is how the
 * server's answer is read and which part of it a press installs: a mod that
 * silently drops out, or a shader without its loader, is a build that does
 * not do what the player asked for.
 */
class AiBuilderTest {

    private fun json(text: String): JsonObject = MojangJson.parseToJsonElement(text).jsonObject

    private fun mod(id: String, slug: String, base: Boolean = false) = AiMod(id, slug, slug, null, "", base)

    @Test
    fun `ответ сервера читается в план или не читается совсем`() {
        val plan = AiBuilder.planFrom(
            json(
                """
                {"title":"Ночь зомби","mcVersion":"1.20.1","loader":"fabric",
                 "mods":[{"projectId":"P7dR8mSH","slug":"fabric-api","title":"Fabric API","base":true},
                         {"projectId":"bad id!","slug":"x"}],
                 "shaders":[{"projectId":"HVnmMxH1","slug":"complementary"}],
                 "shaderLoader":{"projectId":"YL57xq9U","slug":"iris"},
                 "limit":3,"remaining":2,"cached":true,
                 "excluded":[{"slug":"a","title":"A","reason":"wrong_mc","detail":"x"}]}
                """.trimIndent(),
            ),
        )
        assertNotNull("целый план читается", plan)
        plan!!
        assertEquals("загрузчик становится нашим", LoaderKind.FABRIC, plan.loader)
        assertEquals("мод с битым id отброшен, а не ставится наугад", listOf("P7dR8mSH"), plan.mods.map { it.projectId })
        assertEquals("загрузчик шейдеров приходит отдельно", "YL57xq9U", plan.shaderLoader?.projectId)
        assertEquals("кэш помечен — повтор того же текста просит новую сборку", true, plan.cached)

        val cases = listOf(
            """{"mcVersion":"1.20.1","loader":"rift","mods":[{"projectId":"P7dR8mSH"}]}""" to "неизвестный загрузчик — не план",
            """{"mcVersion":"","loader":"fabric","mods":[{"projectId":"P7dR8mSH"}]}""" to "без версии игры сборку не создать",
            """{"mcVersion":"1.20.1","loader":"fabric","mods":[]}""" to "план без модов не план",
        )
        cases.forEach { (body, why) -> assertNull(why, AiBuilder.planFrom(json(body))) }
    }

    @Test
    fun `что ставит одно нажатие`() {
        val api = mod("P7dR8mSH", "fabric-api", base = true)
        val zombies = mod("mMTOWOaA", "zombie-awareness")
        val sodium = mod("AANobbMI", "sodium", base = true)
        val shader = mod("HVnmMxH1", "complementary")
        val iris = mod("YL57xq9U", "iris")
        val plan = AiPlan("t", "1.20.1", LoaderKind.FABRIC, listOf(zombies, api, sodium), shaders = listOf(shader), shaderLoader = iris)

        data class Case(val off: Set<String>, val mods: List<String>, val shaders: List<String>, val why: String)
        val cases = listOf(
            Case(emptySet(), listOf("fabric-api", "sodium", "zombie-awareness", "iris"), listOf("complementary"), "база первой, шейдер тянет Iris"),
            Case(setOf(shader.projectId), listOf("fabric-api", "sodium", "zombie-awareness"), emptyList(), "без шейдера Iris не нужен"),
            Case(setOf(api.projectId), listOf("fabric-api", "sodium", "zombie-awareness", "iris"), listOf("complementary"), "Fabric API снять нельзя: без него моды не стартуют"),
            Case(setOf(zombies.projectId), listOf("fabric-api", "sodium", "iris"), listOf("complementary"), "снятый мод не ставится"),
        )
        cases.forEach { case ->
            val install = AiBuilder.install(plan, case.off)
            assertEquals(case.why, case.mods, install.mods.map { it.slug })
            assertEquals(case.why, case.shaders, install.shaders.map { it.slug })
        }
    }

    @Test
    fun `ошибки сборщика говорят, что делать`() {
        val cases = listOf(
            "сервер ответил 503" to AiBuilder.SOON,
            "сервер ответил 429" to AiBuilder.LIMIT,
            "ThrottlerException: Too Many Requests" to AiBuilder.LIMIT,
            "сервер ответил 502" to "ИИ не ответил — попробуй ещё раз",
            "Лимит на сегодня — 3 сборки, в PLUS — 30" to "Лимит на сегодня — 3 сборки, в PLUS — 30",
        )
        cases.forEach { (message, text) -> assertEquals("ответ «$message»", text, AiBuilder.errorText(IOException(message))) }
        assertEquals("лимит не лечится повтором", false, AiBuilder.retryable(AiBuilder.LIMIT))
        assertEquals("сбой сети лечится повтором", true, AiBuilder.retryable("ИИ не ответил — попробуй ещё раз"))
    }

    @Test
    fun `пропущенные моды названы по одному разу`() {
        val lines = AiBuilder.excludedLines(
            listOf(
                AiExcluded("a", "Alpha", "quarantine", ""),
                AiExcluded("a", "Alpha", "quarantine", ""),
                AiExcluded("b", "Beta", "unknown", "сломан"),
            ),
        )
        assertEquals("причина сервера переводится, неизвестная берётся из detail", listOf("Alpha — ломает запуск на этой версии", "Beta — сломан"), lines)
    }
}
