package net.millida.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A server writes its greeting in the game's own colour codes. Printing them as
 * "§a" turns the line into rubbish, and stripping them makes every server look
 * the same - so the launcher paints the line the way the game does, in the
 * game's monospace font, where the spaces a server centres its text with still
 * line up.
 */
@Composable
fun MotdText(
    motd: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    color: Color = millida.fgSubtle,
    style: TextStyle = MillidaType.pixelSmall,
    textAlign: TextAlign = TextAlign.Start,
) {
    val text = remember(motd) { motdAnnotated(motd) }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/**
 * The greeting on the dark screen the game's server list draws it on: game
 * colours are picked for that dark, and on a light plate half of them vanish.
 */
@Composable
fun MotdPlate(
    motd: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    large: Boolean = false,
    minHeight: Dp = 0.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .pixelSurface(MOTD_SCREEN, MillidaCut.control, MOTD_FRAME)
            .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 8.dp),
        contentAlignment = if (large) Alignment.Center else Alignment.CenterStart,
    ) {
        MotdText(
            motd = motd,
            maxLines = maxLines,
            color = MOTD_DEFAULT,
            style = if (large) {
                MillidaType.pixel.copy(lineHeight = 19.sp)
            } else {
                MillidaType.pixelSmall.copy(lineHeight = 15.sp)
            },
            textAlign = if (large) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The game's rules: a colour code also clears bold, italic and the lines, and
 * `§x§R§R§G§G§B§B` or `§#RRGGBB` set a colour outside the sixteen.
 */
fun motdAnnotated(motd: String): AnnotatedString = buildAnnotatedString {
    var color: Color? = null
    var bold = false
    var italic = false
    var strike = false
    var underline = false
    var index = 0
    val plain = StringBuilder()

    fun flush() {
        if (plain.isEmpty()) return
        val span = SpanStyle(
            color = color ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.W700 else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = when {
                strike && underline -> TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                strike -> TextDecoration.LineThrough
                underline -> TextDecoration.Underline
                else -> null
            },
        )
        pushStyle(span)
        append(plain.toString())
        pop()
        plain.setLength(0)
    }

    fun clearFormats() {
        bold = false
        italic = false
        strike = false
        underline = false
    }

    while (index < motd.length) {
        val symbol = motd[index]
        if (symbol == '§' && index + 1 < motd.length) {
            val code = motd[index + 1].lowercaseChar()
            val spread = if (code == 'x') spreadHex(motd, index) else null
            val direct = if (code == '#') directHex(motd, index) else null
            when {
                spread != null -> {
                    flush()
                    color = spread
                    clearFormats()
                    index += SPREAD_HEX_LENGTH
                    continue
                }
                direct != null -> {
                    flush()
                    color = direct
                    clearFormats()
                    index += DIRECT_HEX_LENGTH
                    continue
                }
                code in COLOURS -> {
                    flush()
                    color = COLOURS[code]
                    clearFormats()
                    index += 2
                    continue
                }
                code in "klmnor" -> {
                    flush()
                    when (code) {
                        'l' -> bold = true
                        'o' -> italic = true
                        'm' -> strike = true
                        'n' -> underline = true
                        'r' -> {
                            color = null
                            clearFormats()
                        }
                        else -> Unit
                    }
                    index += 2
                    continue
                }
            }
        }
        plain.append(symbol)
        index += 1
    }
    flush()
}

private const val SPREAD_HEX_LENGTH = 14
private const val DIRECT_HEX_LENGTH = 8

private fun spreadHex(motd: String, start: Int): Color? {
    if (start + SPREAD_HEX_LENGTH > motd.length) return null
    val digits = StringBuilder()
    for (pair in 0 until 6) {
        val marker = motd[start + 2 + pair * 2]
        if (marker != '§') return null
        digits.append(motd[start + 3 + pair * 2])
    }
    return hexColour(digits.toString())
}

private fun directHex(motd: String, start: Int): Color? {
    if (start + DIRECT_HEX_LENGTH > motd.length) return null
    return hexColour(motd.substring(start + 2, start + DIRECT_HEX_LENGTH))
}

private fun hexColour(digits: String): Color? {
    if (digits.length != 6 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return Color(0xFF000000L or digits.toLong(16))
}

/** The palette of the game itself, so the greeting looks the same as in the list. */
private val COLOURS: Map<Char, Color> = mapOf(
    '0' to Color(0xFF000000),
    '1' to Color(0xFF0000AA),
    '2' to Color(0xFF00AA00),
    '3' to Color(0xFF00AAAA),
    '4' to Color(0xFFAA0000),
    '5' to Color(0xFFAA00AA),
    '6' to Color(0xFFFFAA00),
    '7' to Color(0xFFAAAAAA),
    '8' to Color(0xFF555555),
    '9' to Color(0xFF5555FF),
    'a' to Color(0xFF55FF55),
    'b' to Color(0xFF55FFFF),
    'c' to Color(0xFFFF5555),
    'd' to Color(0xFFFF55FF),
    'e' to Color(0xFFFFFF55),
    'f' to Color(0xFFFFFFFF),
)

/** The server list of the game is dark in both themes, and so is its greeting. */
private val MOTD_SCREEN = Color(0xFF121314)
private val MOTD_DEFAULT = Color(0xFF7ED96F)
private val MOTD_FRAME = PixelFrame(
    outline = Color(0x33000000),
    outlineWidth = 1.dp,
    top = Color(0x66000000),
    topWidth = 3.dp,
)

/** Server tags come as codes; players read words. */
fun categoryLabel(code: String): String = when (code.uppercase()) {
    "SURVIVAL" -> "Выживание"
    "VANILLA" -> "Ванилла"
    "MMORPG" -> "MMORPG"
    "ONEBLOCK" -> "OneBlock"
    "ANARCHY" -> "Анархия"
    "MINIGAMES" -> "Мини-игры"
    "CREATIVE" -> "Креатив"
    "SKYBLOCK" -> "SkyBlock"
    "ROLEPLAY" -> "Ролевые"
    "TECHNIC" -> "Технические"
    "PVP" -> "PvP"
    "ADVENTURE" -> "Приключения"
    "ECONOMY" -> "Экономика"
    "FACTIONS" -> "Фракции"
    "HARDCORE" -> "Хардкор"
    "MODDED" -> "С модами"
    "OTHER" -> "Другое"
    else -> code
}
