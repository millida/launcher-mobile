package net.millida.launcher.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.friends.Eltaller
import net.millida.launcher.core.friends.Eltaller.Piece.Emoji
import net.millida.launcher.core.friends.Eltaller.Piece.Words
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pixel emoji travel as `:elt_NN:` text written by the desktop launcher
 * (src/lib/eltaller.tsx); the app must split it exactly the same way, or the
 * same message looks different on the phone.
 */
class EltallerTest {

    private class Case(val why: String, val text: String, val want: Eltaller.Parsed)

    @Test
    fun `коды смайлов разбираются как в десктопе`() {
        val cases = listOf(
            Case("обычный текст остаётся одним куском и не крупный", "привет", Eltaller.Parsed(listOf(Words("привет")), big = false)),
            Case("один смайл без текста рисуется крупно, как .elt-only", ":elt_07:", Eltaller.Parsed(listOf(Emoji("07")), big = true)),
            Case(
                "три смайла через пробел ещё крупные",
                ":elt_01: :elt_02::elt_03:",
                Eltaller.Parsed(listOf(Emoji("01"), Words(" "), Emoji("02"), Emoji("03")), big = true),
            ),
            Case(
                "четыре — уже в строку: десктоп ограничивает крупный вид тремя",
                ":elt_01::elt_02::elt_03::elt_04:",
                Eltaller.Parsed(listOf(Emoji("01"), Emoji("02"), Emoji("03"), Emoji("04")), big = false),
            ),
            Case(
                "смайл среди слов остаётся в строке и не съедает текст",
                "ну ты :elt_27: даёшь",
                Eltaller.Parsed(listOf(Words("ну ты "), Emoji("27"), Words(" даёшь")), big = false),
            ),
            Case(
                "номер вне набора — текст: новый клиент не должен рисовать пустую картинку",
                ":elt_56: :elt_00:",
                Eltaller.Parsed(listOf(Words(":elt_56: :elt_00:")), big = false),
            ),
            Case(
                "неизвестный код рядом с известным остаётся текстом",
                ":elt_99::elt_05:",
                Eltaller.Parsed(listOf(Words(":elt_99:"), Emoji("05")), big = false),
            ),
            Case(
                "три цифры и одна цифра — не коды",
                ":elt_100: :elt_1:",
                Eltaller.Parsed(listOf(Words(":elt_100: :elt_1:")), big = false),
            ),
            Case("пустое сообщение — без кусков", "", Eltaller.Parsed(emptyList(), big = false)),
        )
        cases.forEach { case -> assertEquals(case.why, case.want, Eltaller.parse(case.text)) }
    }

    @Test
    fun `вставленный код читается обратно тем же смайлом`() {
        Eltaller.ids.forEach { id ->
            assertEquals("код $id из выбора должен разобраться в тот же смайл", listOf(Emoji(id)), Eltaller.parse(Eltaller.code(id)).pieces)
        }
    }

    @Test
    fun `недавние встают первыми и каждый смайл в выборе один раз`() {
        val order = Eltaller.withRecent(listOf("41", "07"))
        assertEquals("недавние первыми, как в десктопе", listOf("41", "07"), order.take(2))
        assertEquals("в сетке весь набор без повторов", Eltaller.COUNT, order.distinct().size)
        assertEquals("в сетке весь набор без повторов", Eltaller.COUNT, order.size)
        val recent = (1..10).fold(emptyList<String>()) { acc, n -> Eltaller.remembered(acc, n.toString().padStart(2, '0')) }
        assertEquals("недавних не больше восьми, свежий первым", listOf("10", "09", "08", "07", "06", "05", "04", "03"), recent)
        assertEquals("чужие значения из настроек отбрасываются", listOf("01"), Eltaller.cleanRecent(listOf("01", "zz", "99", "01")))
    }

    @Test
    fun `в уведомлении код становится обычным смайлом, неизвестный остаётся`() {
        assertEquals("ну ${Eltaller.ALIASES.getValue("27")}!", Eltaller.plain("ну :elt_27:!"))
        assertEquals(":elt_77:", Eltaller.plain(":elt_77:"))
    }

    private val assets = File("../app/src/main/assets/emoji/eltaller")
    private val desktop = File("../../millida-launcher/public/emoji/eltaller")

    @Test
    fun `подписи смайлов совпадают с manifest json приложения`() {
        val manifest = Json.parseToJsonElement(File(assets, "manifest.json").readText()).jsonObject
        val shipped = manifest.getValue("items").jsonArray.associate { item ->
            val fields = item.jsonObject
            fields.getValue("id").jsonPrimitive.content to fields.getValue("emoji").jsonPrimitive.content
        }
        assertEquals("ALIASES разошлись с manifest.json — уведомления покажут не тот смайл", shipped, Eltaller.ALIASES)
        val missing = Eltaller.ids.filter { !File(assets, "$it.webp").isFile }
        assertEquals("у каждого смайла должна быть картинка в assets, иначе в чате пустое место", emptyList<String>(), missing)
    }

    @Test
    fun `картинки приложения байт в байт как в десктопе`() {
        assumeTrue("десктопного лаунчера рядом нет — сверять не с чем", desktop.isDirectory)
        val drift = desktop.listFiles().orEmpty().map { it.name }.sorted().filter { name ->
            val mine = File(assets, name)
            !mine.isFile || !mine.readBytes().contentEquals(File(desktop, name).readBytes())
        }
        assertEquals("набор в assets разошёлся с десктопом — скопируй millida-launcher/public/emoji/eltaller", emptyList<String>(), drift)
    }
}
