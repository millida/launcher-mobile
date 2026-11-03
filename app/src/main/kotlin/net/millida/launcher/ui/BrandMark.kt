package net.millida.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.R

/** The green Millida sign alone, as on the desktop title bar and sign-in screen. */
@Composable
fun BrandSign(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_brand_logo), contentDescription = "Millida", modifier = modifier.size(size))
}

private val WordmarkFont = FontFamily(Font(R.font.unbounded_medium, FontWeight.Medium))

/**
 * Sign plus the wordmark exactly as millida.net draws it (ui-kit/millida/Logo.tsx): Unbounded 500,
 * upper case, tracking 0.02em, ® after the word, text 22 to a 36 sign with a 12 gap. The owner
 * twice caught a mixed-case bold «Millida» here as an old font (26.10).
 */
@Composable
fun BrandLockup(signSize: Dp = 22.dp, color: Color = millida.fg, modifier: Modifier = Modifier) {
    val fontSize = signSize.value * 22f / 36f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandSign(signSize)
        Spacer(Modifier.width(signSize * (12f / 36f)))
        androidx.compose.material3.Text(
            buildAnnotatedString {
                append("MILLIDA")
                withStyle(SpanStyle(fontSize = (fontSize * 0.45f).sp, baselineShift = BaselineShift(0.9f))) { append("®") }
            },
            style = TextStyle(
                fontFamily = WordmarkFont,
                fontWeight = FontWeight.Medium,
                fontSize = fontSize.sp,
                lineHeight = fontSize.sp,
                letterSpacing = (fontSize * 0.02f).sp,
            ),
            color = color,
            maxLines = 1,
        )
    }
}
