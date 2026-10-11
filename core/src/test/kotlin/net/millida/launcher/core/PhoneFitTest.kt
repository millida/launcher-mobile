package net.millida.launcher.core

import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.mojang.MojangJson
import net.millida.launcher.core.packs.LaunchShape
import net.millida.launcher.core.packs.PhoneBlock
import net.millida.launcher.core.packs.PhoneCard
import net.millida.launcher.core.packs.PhoneFit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone refuses a pack before the download, not after gigabytes. What is
 * pinned is which packs are refused and which still go through: a false
 * refusal hides a pack that plays, a missed one wastes the player's traffic.
 */
class PhoneFitTest {

    @After
    fun unwire() = PhoneFit.wire(legacyRuntime = { false }, deviceTotalMb = { 0 })

    private data class Case(
        val loader: String,
        val game: String,
        val launch: LaunchShape,
        val legacyRuntime: Boolean,
        val expected: PhoneBlock?,
        val why: String,
    )

    @Test
    fun `какие сборки телефон не запустит`() {
        val plain = LaunchShape.PLAIN
        val cases = listOf(
            Case("forge", "1.12.2", plain, false, PhoneBlock.OLD_FORGE, "Forge до 1.13 идёт только с бетой Java 8"),
            Case("Forge", "1.7.10", plain, false, PhoneBlock.OLD_FORGE, "регистр загрузчика из карточки не спасает"),
            Case("forge", "1.12.2", plain, true, null, "с включённой бетой старый Forge пускается"),
            Case("forge", "1.13", plain, false, null, "с 1.13 Forge ставится на телефоне"),
            Case("forge", "1.20.1", plain, false, null, "современный Forge играет"),
            Case("neoforge", "1.21.1", plain, false, null, "NeoForge не бывает старым"),
            Case("fabric", "1.12.2", plain, false, null, "правило только про Forge"),
            Case("forge", "", plain, false, null, "без версии решает установщик и говорит сам"),
            Case("vanilla", "1.8.9", plain, false, null, "ванильная игра запускается"),
            Case("forge", "1.7.10", LaunchShape.OWN_DECLARED, false, PhoneBlock.OWN_LAUNCH, "свой запуск написан для компьютера — только с бетой"),
            Case("fabric", "1.20.1", LaunchShape.OWN_DECLARED, true, null, "с бетой загрузчик карточки не решает, решает архив"),
            Case("forge", "1.7.10", LaunchShape.OWN_READY, false, PhoneBlock.OWN_LAUNCH, "читаемый свой запуск на Java 21 тоже ждёт беты: на телефоне не проверен"),
            Case("forge", "1.7.10", LaunchShape.OWN_READY, true, null, "с бетой читаемый свой запуск идёт"),
            Case("fabric", "1.20.1", LaunchShape.OWN_BROKEN, false, PhoneBlock.BROKEN_LAUNCH, "нечитаемое описание запуска не превращается в обычную сборку"),
            Case("fabric", "1.20.1", LaunchShape.OWN_BROKEN, true, PhoneBlock.BROKEN_LAUNCH, "бета не чинит битое описание"),
            Case("forge", "1.12.2", LaunchShape.OWN_LEGACY, false, PhoneBlock.OWN_LAUNCH, "свой запуск на Java 8 ждёт беты"),
            Case("forge", "1.12.2", LaunchShape.OWN_LEGACY, true, null, "с бетой свой запуск на Java 8 пускается"),
        )
        cases.forEach { case ->
            assertEquals(
                "${case.loader} ${case.game}, запуск=${case.launch}, бета=${case.legacyRuntime}: ${case.why}",
                case.expected,
                PhoneFit.verdict(case.loader, case.game, case.launch, case.legacyRuntime),
            )
        }
    }

    @Test
    fun `бета выключена, пока приложение её не включило, и читается на каждом вопросе`() {
        assertFalse("без настройки игрока старый Forge обязан отказывать до скачивания гигабайтов", PhoneFit.legacyRuntime())
        assertEquals(PhoneBlock.OLD_FORGE, PhoneFit.verdict("forge", "1.7.10"))

        var switch = true
        PhoneFit.wire(legacyRuntime = { switch }, deviceTotalMb = { 0 })
        assertEquals("включённая бета действует без перезапуска", null, PhoneFit.verdict("forge", "1.7.10"))
        switch = false
        assertEquals("выключенная бета снова отказывает — значение не запомнено при подключении", PhoneBlock.OLD_FORGE, PhoneFit.verdict("forge", "1.7.10"))
    }

    @Test
    fun `карточка каталога решает до скачивания`() {
        data class CardCase(val card: PhoneCard, val beta: Boolean, val totalMb: Int, val expected: PhoneBlock?, val why: String)

        fun mb(value: Long) = value * 1024 * 1024
        val oneBlock = PhoneCard("forge", "1.7.10", ownLaunch = true, sizeBytes = 985_672_963)
        val arcania = PhoneCard("fabric", "1.20.1", ownLaunch = true, accessRequired = true, sizeBytes = 2_279_000_000)
        val deceased = PhoneCard("forge", "1.20.1", accessRequired = true, sizeBytes = 677_000_000)
        val atm10 = PhoneCard("neoforge", "1.21.1", accessRequired = true, sizeBytes = mb(1_343))
        val cases = listOf(
            CardCase(oneBlock, false, 8_000, PhoneBlock.OWN_LAUNCH, "OneBlock — свой запуск под ПК, без беты скрыт"),
            CardCase(oneBlock, true, 8_000, null, "с бетой OneBlock можно пробовать: архив проверится до запуска"),
            CardCase(arcania, false, 12_000, PhoneBlock.PAID_OWN_LAUNCH, "платный свой запуск не продаётся на телефоне"),
            CardCase(arcania, true, 12_000, PhoneBlock.PAID_OWN_LAUNCH, "бета не открывает покупку того, что проверяется только после оплаты"),
            CardCase(deceased, false, 8_000, null, "обычный Forge 1.20.1 ставится"),
            CardCase(atm10, false, 3_014, PhoneBlock.TOO_HEAVY, "1343 МБ клиента + 512 МБ игры не влезают в 3014 − 1160 МБ"),
            CardCase(atm10, false, 3_015, null, "ровно на границе памяти ещё пускаем"),
            CardCase(atm10, false, 0, null, "неизвестная память телефона не повод прятать сборку"),
            CardCase(atm10.copy(sizeBytes = 0), false, 2_000, null, "неизвестный размер не повод прятать сборку"),
            CardCase(PhoneCard("forge", "1.12.2"), true, 2_000, null, "с бетой старый Forge без размера проходит"),
            CardCase(PhoneCard("forge", "1.12.2", sizeBytes = mb(100)), false, 2_000, PhoneBlock.OLD_FORGE, "правило загрузчика раньше правила памяти"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, PhoneFit.cardVerdict(case.card, case.beta, case.totalMb))
        }
    }

    @Test
    fun `что внутри своего запуска телефон не потянет`() {
        val cases = listOf(
            listOf("millida-pack-launch.json", "libraries/org/lwjgl/lwjgl-sdl/3.4.2/lwjgl-sdl-3.4.2.jar") to PhoneBlock.SDL_WINDOW to
                "OneBlock: lwjgl3ify 3 открывает окно через SDL3, в рантайме телефона только GLFW",
            listOf("natives/windows/x64/org/lwjgl/sdl/SDL3.dll") to PhoneBlock.SDL_WINDOW to "SDL3 виден и по нативу",
            listOf("natives/mustdie/x86-64/OpenGL.dll", "natives/mustdie/x86-64/lwjgl.dll") to PhoneBlock.DESKTOP_NATIVE to
                "Arcania: защита грузит свою OpenGL.dll, сборки под arm64 у неё нет",
            listOf("natives/windows/x64/org/lwjgl/lwjgl.dll", "natives/linux/x64/liblwjgl_opengl.so", "natives/windows/x64/OpenAL.dll") to null to
                "нативы LWJGL и OpenAL заменяет рантайм телефона",
            listOf("natives/windows/x64/vcruntime140.dll", "natives/windows/twitchsdk.dll") to null to
                "среда C Windows и Twitch 1.7.10 на телефоне не нужны",
            listOf("natives/windows/guard.dll", "natives/android-arm64/libguard.so") to null to
                "сборка сама привезла телефонную версию своих нативов",
            listOf("mods/discord-rpc.dll", "mods/lwjgl3ify-3.0.31.jar") to null to
                "нативы вне natives/ не попадают в путь библиотек, мод lwjgl3ify — не SDL",
            emptyList<String>() to null to "пустой список не отказ",
        )
        cases.forEach { (case, why) ->
            assertEquals("${case.first}: $why", case.second, PhoneFit.ownLaunchProblem(case.first, legacyRuntime = false, sdlRuntime = false))
        }
    }

    @Test
    fun `сборка с окном SDL3 идёт только с бетой и опубликованным SDL3`() {
        data class SdlCase(val paths: List<String>, val beta: Boolean, val sdl: Boolean, val expected: PhoneBlock?, val why: String)

        val oneBlock = listOf("millida-pack-launch.json", "libraries/org/lwjgl/lwjgl-sdl/3.4.2/lwjgl-sdl-3.4.2.jar", "natives/windows/x64/org/lwjgl/sdl/SDL3.dll")
        val cases = listOf(
            SdlCase(oneBlock, false, false, PhoneBlock.SDL_WINDOW, "ни беты, ни SDL3 — отказ"),
            SdlCase(oneBlock, true, false, PhoneBlock.SDL_WINDOW, "бета без SDL3 в манифесте не поможет"),
            SdlCase(oneBlock, false, true, PhoneBlock.SDL_WINDOW, "SDL3 есть, но бета выключена — путь не проверен, отказ"),
            SdlCase(oneBlock, true, true, null, "бета и SDL3 — OneBlock пускается"),
            SdlCase(oneBlock + "natives/windows/x64/guard.dll", true, true, PhoneBlock.DESKTOP_NATIVE, "SDL3 заменяет окно, а чужой натив сборки всё равно не запустится"),
            SdlCase(
                oneBlock + listOf(
                    "natives/linux/x64/org/lwjgl/shaderc/libshaderc.so",
                    "natives/windows/x64/org/lwjgl/spvc/spirv-cross.dll",
                    "natives/macos/arm64/org/lwjgl/freetype/libfreetype.dylib",
                    "natives/jinput-raw_64.dll",
                ),
                true,
                true,
                null,
                "настоящий состав OneBlock 26.10.20: shaderc и spirv-cross — модули LWJGL, их меняет рантайм телефона",
            ),
            SdlCase(listOf("natives/windows/x64/lwjgl.dll"), false, false, null, "сборка без SDL3 от беты не зависит"),
        )
        cases.forEach { case ->
            assertEquals(case.why, case.expected, PhoneFit.ownLaunchProblem(case.paths, case.beta, case.sdl))
        }
        assertTrue("SDL3 узнаётся и по пути Windows", PhoneFit.sdlFile("natives\\windows\\x64\\SDL3.dll"))
        assertFalse("мод lwjgl3ify — не библиотека SDL", PhoneFit.sdlFile("mods/lwjgl3ify-3.0.31.jar"))
        assertTrue(
            "когда SDL3 опубликован, отказ подсказывает бету",
            PhoneFit.message(PhoneBlock.SDL_WINDOW, "OneBlock", "1.7.10", legacyRuntime = false, sdlRuntime = true).contains(PhoneFit.BETA_HINT),
        )
        assertFalse(
            "без SDL3 бета не поможет — подсказка обманула бы",
            PhoneFit.message(PhoneBlock.SDL_WINDOW, "OneBlock", "1.7.10", legacyRuntime = false, sdlRuntime = false).contains(PhoneFit.BETA_HINT),
        )
    }

    @Test
    fun `оглавление архива переводится в форму запуска`() {
        assertEquals(LaunchShape.OWN_DECLARED, PhoneFit.indexShape(listOf("Pack/millida-pack-launch.json", "Pack/libraries/a.jar")))
        assertEquals(LaunchShape.PLAIN, PhoneFit.indexShape(listOf("mods/a.jar")))
        assertEquals("без оглавления действует правило карточки", LaunchShape.PLAIN, PhoneFit.indexShape(null))
        assertEquals(
            "пути считаются от папки описания: проверка нативов смотрит в natives/ сборки, а не обёртки",
            listOf("millida-pack-launch.json", "natives/a.dll"),
            PhoneFit.launchRelative(listOf("Pack/millida-pack-launch.json", "Pack/natives/a.dll")),
        )
    }

    @Test
    fun `свой запуск виден по оглавлению архива`() {
        val cases = listOf(
            listOf("mods/a.jar", "millida-pack-launch.json") to true,
            listOf("Pack/millida-pack-launch.json", "Pack/mods/a.jar") to true,
            listOf("/millida-pack-launch.json") to true,
            listOf("config/millida-pack-launch.json", "mods/a.jar", "options.txt") to false,
            listOf("a/b/millida-pack-launch.json") to false,
            listOf("mods/millida-pack-launch.json.bak", "readme.txt") to false,
            emptyList<String>() to false,
        )
        cases.forEach { (entries, expected) ->
            assertEquals(
                "$entries: описание запуска читается из корня сборки, архив в одной папке разворачивается",
                expected,
                PhoneFit.indexHasOwnLaunch(entries),
            )
        }
    }

    @Test
    fun `свой запуск узнаётся по карточке`() {
        val cases = listOf(
            """{"protectedContent": true}""" to true,
            """{"ownLaunch": true}""" to true,
            """{"protectedContent": "true"}""" to false,
            """{"protectedContent": false}""" to false,
            """{}""" to false,
        )
        cases.forEach { (body, expected) ->
            assertEquals("$body: строка «true» — не флаг", expected, PhoneFit.ownLaunchIn(MojangJson.parseToJsonElement(body).jsonObject))
        }
    }

    @Test
    fun `отказ объясняет, где играть, и подсказывает бету только там, где она поможет`() {
        val text = PhoneFit.message(PhoneBlock.OLD_FORGE, "TechnoMagic", "1.12.2", legacyRuntime = false)
        assertTrue(text.contains("компьютере") && text.contains("1.12.2") && text.contains("TechnoMagic"))
        assertTrue("выключенная бета названа по пути в настройках", text.contains(PhoneFit.BETA_HINT))
        assertTrue(PhoneFit.message(PhoneBlock.OWN_LAUNCH, "OneBlock", "1.7.10", legacyRuntime = false).contains(PhoneFit.BETA_HINT))
        assertFalse(
            "платную сборку со своим запуском бета не откроет — подсказка обманула бы",
            PhoneFit.message(PhoneBlock.PAID_OWN_LAUNCH, "Arcania", "1.20.1", legacyRuntime = false).contains(PhoneFit.BETA_HINT),
        )
        assertFalse(
            "SDL3 бета не лечит",
            PhoneFit.message(PhoneBlock.SDL_WINDOW, "OneBlock", "1.7.10", legacyRuntime = true, sdlRuntime = false).contains(PhoneFit.BETA_HINT),
        )
    }
}
