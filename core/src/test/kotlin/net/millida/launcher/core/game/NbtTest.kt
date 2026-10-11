package net.millida.launcher.core.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class NbtTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class Case(val label: String, val bytes: ByteArray, val why: String)

    private fun gzip(block: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(out)).use { it.block() }
        return out.toByteArray()
    }

    private fun roundTrip(document: NbtDocument): NbtDocument? {
        val out = ByteArrayOutputStream()
        Nbt.write(out, document)
        return Nbt.read(ByteArrayInputStream(out.toByteArray()))
    }

    private fun render(tag: NbtTag): String = when (tag) {
        is NbtByte -> "b${tag.value}"
        is NbtShort -> "s${tag.value}"
        is NbtInt -> "i${tag.value}"
        is NbtLong -> "l${tag.value}"
        is NbtFloat -> "f${tag.value}"
        is NbtDouble -> "d${tag.value}"
        is NbtString -> "t\"${tag.value}\""
        is NbtByteArray -> "B${tag.value.toList()}"
        is NbtIntArray -> "I${tag.value.toList()}"
        is NbtLongArray -> "L${tag.value.toList()}"
        is NbtList -> "list<${tag.elementType}>${tag.items.map { render(it) }}"
        is NbtCompound -> tag.values.entries.joinToString(",", "{", "}") { "${it.key}=${render(it.value)}" }
    }

    @Test
    fun `каждый тип тега переживает запись и чтение без потерь`() {
        val inner = NbtCompound(linkedMapOf("Name" to NbtString("Мир Артёма"), "Seed" to NbtLong(-42L)))
        val root = NbtCompound(
            linkedMapOf(
                "byte" to NbtByte(Byte.MIN_VALUE),
                "short" to NbtShort(Short.MAX_VALUE),
                "int" to NbtInt(Int.MIN_VALUE),
                "long" to NbtLong(Long.MAX_VALUE),
                "float" to NbtFloat(1.5f),
                "double" to NbtDouble(-0.125),
                "string" to NbtString("Привет, мир"),
                "empty" to NbtString(""),
                "bytes" to NbtByteArray(byteArrayOf(0, -1, 127)),
                "ints" to NbtIntArray(intArrayOf(1, -2, Int.MAX_VALUE)),
                "longs" to NbtLongArray(longArrayOf(Long.MIN_VALUE, 0L)),
                "list" to NbtList(Nbt.TAG_INT, listOf(NbtInt(1), NbtInt(2))),
                "nested" to NbtList(Nbt.TAG_COMPOUND, listOf(inner, NbtCompound(emptyMap()))),
                "Data" to inner,
            ),
        )
        val back = roundTrip(NbtDocument("root", root, complete = true))

        assertNotNull("записанный мир не прочитался обратно — игрок теряет level.dat", back)
        assertEquals("имя корня потерялось", "root", back!!.name)
        assertTrue("целый файл помечен как обрезанный и перестанет перезаписываться", back.complete)
        assertEquals("перезапись level.dat изменила данные мира", render(root), render(back.root))
        assertEquals(
            "порядок ключей сменился — игра читает, но diff файла раздувается",
            root.values.keys.toList(),
            back.root.values.keys.toList(),
        )
    }

    @Test
    fun `пустой список сохраняет тип элементов`() {
        val root = NbtCompound(linkedMapOf("Tags" to NbtList(Nbt.TAG_STRING, emptyList())))
        val back = roundTrip(NbtDocument("", root, complete = true))
        val list = back?.root?.get("Tags") as? NbtList
        assertNotNull("пустой список пропал при перезаписи", list)
        assertEquals("тип пустого списка сбит — игра откажется читать поле", Nbt.TAG_STRING, list!!.elementType)
        assertTrue("в пустом списке появились элементы", list.items.isEmpty())
    }

    @Test
    fun `битый или чужой файл читается как отсутствующий, а не роняет экран`() {
        val cases = listOf(
            Case("пустой поток", ByteArray(0), "файл нулевой длины после сбоя записи"),
            Case("не gzip", byteArrayOf(10, 0, 0, 0), "level.dat всегда сжат, несжатый — чужой файл"),
            Case("битый gzip", byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 1, 2, 3), "оборванная загрузка мира"),
            Case("корень-список", gzip { writeByte(Nbt.TAG_LIST); writeUTF(""); writeByte(0); writeInt(0) }, "корень level.dat обязан быть compound"),
            Case("корень без имени", gzip { writeByte(Nbt.TAG_COMPOUND) }, "файл обрезан сразу после типа"),
        )
        for (case in cases) {
            assertNull("${case.label}: ${case.why}", Nbt.read(ByteArrayInputStream(case.bytes)))
        }
    }

    @Test
    fun `незнакомый тег останавливает чтение и помечает дерево неполным`() {
        val bytes = gzip {
            writeByte(Nbt.TAG_COMPOUND); writeUTF("")
            writeByte(Nbt.TAG_INT); writeUTF("SpawnX"); writeInt(100)
            writeByte(13); writeUTF("future"); writeInt(7)
            writeByte(Nbt.TAG_INT); writeUTF("SpawnZ"); writeInt(200)
            writeByte(Nbt.TAG_END)
        }
        val document = Nbt.read(ByteArrayInputStream(bytes))
        assertNotNull("карточка мира с тегом из новой версии игры не показалась", document)
        assertFalse("неполное дерево помечено полным — перезапись сотрёт остаток мира", document!!.complete)
        assertEquals("прочитанное до незнакомого тега потеряно", 100L, document.root.number("SpawnX"))
        assertNull("за незнакомым тегом нельзя доверять ни одному байту", document.root["SpawnZ"])
    }

    @Test
    fun `обрезанный и раздутый файл не выдаётся за целый`() {
        val cases = listOf(
            Case(
                "обрыв посреди compound",
                gzip { writeByte(Nbt.TAG_COMPOUND); writeUTF(""); writeByte(Nbt.TAG_INT); writeUTF("A"); writeInt(1); writeByte(Nbt.TAG_STRING); writeUTF("B") },
                "запись прервалась, а файл остался",
            ),
            Case(
                "массив байт на 2 ГБ",
                gzip { writeByte(Nbt.TAG_COMPOUND); writeUTF(""); writeByte(Nbt.TAG_INT); writeUTF("A"); writeInt(1); writeByte(Nbt.TAG_BYTE_ARRAY); writeUTF("X"); writeInt(Int.MAX_VALUE) },
                "подделанный размер не должен выделять гигабайты на телефоне",
            ),
            Case(
                "отрицательный размер массива",
                gzip { writeByte(Nbt.TAG_COMPOUND); writeUTF(""); writeByte(Nbt.TAG_INT); writeUTF("A"); writeInt(1); writeByte(Nbt.TAG_LONG_ARRAY); writeUTF("X"); writeInt(-1) },
                "отрицательная длина — признак мусора",
            ),
            Case(
                "список на миллиард элементов",
                gzip { writeByte(Nbt.TAG_COMPOUND); writeUTF(""); writeByte(Nbt.TAG_INT); writeUTF("A"); writeInt(1); writeByte(Nbt.TAG_LIST); writeUTF("X"); writeByte(Nbt.TAG_INT); writeInt(1_000_000_000) },
                "список с честным типом и нечестным размером не должен выделять память",
            ),
            Case(
                "список незнакомого типа",
                gzip { writeByte(Nbt.TAG_COMPOUND); writeUTF(""); writeByte(Nbt.TAG_INT); writeUTF("A"); writeInt(1); writeByte(Nbt.TAG_LIST); writeUTF("X"); writeByte(42); writeInt(3) },
                "тип элементов вне таблицы — длину элементов узнать нельзя",
            ),
        )
        for (case in cases) {
            val document = Nbt.read(ByteArrayInputStream(case.bytes))
            assertNotNull("${case.label}: прочитанное до поломки должно показаться (${case.why})", document)
            assertFalse("${case.label}: дерево помечено полным (${case.why})", document!!.complete)
            assertEquals("${case.label}: первое поле потеряно (${case.why})", 1L, document.root.number("A"))
        }
    }

    @Test
    fun `слишком глубокая вложенность не роняет чтение`() {
        val depth = 40
        val bytes = gzip {
            writeByte(Nbt.TAG_COMPOUND); writeUTF("")
            repeat(depth) { writeByte(Nbt.TAG_COMPOUND); writeUTF("n") }
            repeat(depth + 1) { writeByte(Nbt.TAG_END) }
        }
        val document = Nbt.read(ByteArrayInputStream(bytes))
        assertNotNull("глубокий файл должен читаться частично, а не падать", document)
        assertFalse("обрезанное по глубине дерево нельзя перезаписывать", document!!.complete)
        assertNotNull("верхний уровень потерян", document.root.compound("n"))
    }

    @Test
    fun `помощники compound читают числа любой ширины и не путают типы`() {
        val root = NbtCompound(
            linkedMapOf(
                "b" to NbtByte(-3),
                "s" to NbtShort(300),
                "i" to NbtInt(70_000),
                "l" to NbtLong(5_000_000_000L),
                "t" to NbtString("42"),
                "c" to NbtCompound(emptyMap()),
            ),
        )
        assertEquals("байт не расширился до числа", -3L, root.number("b"))
        assertEquals("short не расширился до числа", 300L, root.number("s"))
        assertEquals("int не расширился до числа", 70_000L, root.number("i"))
        assertEquals("long прочитан с потерей", 5_000_000_000L, root.number("l"))
        assertNull("строка с цифрами не число — иначе битое поле покажется значением", root.number("t"))
        assertNull("отсутствующее поле не ноль", root.number("missing"))
        assertEquals("строка не прочиталась", "42", root.text("t"))
        assertNull("число не строка", root.text("i"))
        assertNotNull("вложенный compound не найден", root.compound("c"))
        assertNull("строка не compound", root.compound("t"))

        val changed = root.with("t", NbtString("новое"))
        assertEquals("with не заменил значение", "новое", changed.text("t"))
        assertEquals("with изменил исходное дерево — отмена правки станет невозможной", "42", root.text("t"))
        assertEquals("with переставил ключи", root.values.keys.toList(), changed.values.keys.toList())
    }

    @Test
    fun `файл, которого нет, читается как null`() {
        assertNull("отсутствующий level.dat не должен бросать", Nbt.read(File(tmp.root, "level.dat")))
        assertNull("папка вместо файла не должна бросать", Nbt.read(tmp.newFolder("level.dat_dir")))
        val file = tmp.newFile("level.dat")
        file.outputStream().use { Nbt.write(it, NbtDocument("", NbtCompound(linkedMapOf("v" to NbtInt(19133))), true)) }
        assertEquals("файл с диска прочитан не так, как записан", 19133L, Nbt.read(file)?.root?.number("v"))
    }
}
