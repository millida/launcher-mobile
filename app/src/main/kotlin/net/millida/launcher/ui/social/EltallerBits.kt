package net.millida.launcher.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.Prefs
import net.millida.launcher.core.friends.Eltaller
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.home.AssetImage

/** Emoji sizes of the desktop chat (src/styles/pixel/emoji.css): in a line, alone in a message, in a quote. */
internal enum class EltSize(val side: TextUnit) {
    Line(24.sp),
    Big(48.sp),
    Quote(18.sp),
}

private const val PICKER_COLUMNS = 8

@Composable
internal fun EltImage(id: String, modifier: Modifier = Modifier) {
    AssetImage(Eltaller.assetPath(id), modifier, scale = ContentScale.Fit)
}

/**
 * Text whose `:elt_NN:` codes are drawn as the desktop's pixel emoji. A
 * message of up to three emoji and nothing else is drawn large, unless
 * [quote] asks for the small quote size.
 */
@Composable
internal fun EltText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    quote: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val parsed = remember(text) { Eltaller.parse(text) }
    if (!parsed.hasEmoji) {
        Text(text, modifier, style = style, color = color, maxLines = maxLines, overflow = overflow)
        return
    }
    val size = when {
        quote -> EltSize.Quote
        parsed.big -> EltSize.Big
        else -> EltSize.Line
    }
    val annotated = remember(parsed) {
        buildAnnotatedString {
            parsed.pieces.forEach { piece ->
                when (piece) {
                    is Eltaller.Piece.Words -> append(piece.text)
                    is Eltaller.Piece.Emoji -> appendInlineContent(piece.id, Eltaller.ALIASES[piece.id] ?: Eltaller.code(piece.id))
                }
            }
        }
    }
    val inline = remember(parsed, size) {
        parsed.pieces.filterIsInstance<Eltaller.Piece.Emoji>().map { it.id }.distinct().associateWith { id ->
            InlineTextContent(Placeholder(size.side, size.side, PlaceholderVerticalAlign.TextCenter)) {
                EltImage(id, Modifier.fillMaxSize())
            }
        }
    }
    Text(annotated, modifier, style = style, color = color, maxLines = maxLines, overflow = overflow, inlineContent = inline)
}

/** The desktop's emoji grid: one set, recent first, the picked code goes to the end of the draft. */
@Composable
internal fun EltPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val order = remember { Eltaller.withRecent(prefs.chatEmojiRecent) }
    MBottomSheet(onDismiss = onDismiss) {
        order.chunked(PICKER_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { id ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(MillidaShape.mini)
                            .clickable {
                                prefs.chatEmojiRecent = Eltaller.remembered(prefs.chatEmojiRecent, id)
                                onPick(Eltaller.code(id))
                            }
                            .semantics { contentDescription = Eltaller.ALIASES[id] ?: Eltaller.code(id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        EltImage(id, Modifier.size(28.dp))
                    }
                }
                repeat(PICKER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
