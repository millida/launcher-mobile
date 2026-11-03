package net.millida.launcher.ui.shop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.pixelSurface

/** «−40%» in the corner of a picture, the desktop's `sv-off`. */
@Composable
internal fun DiscountBadge(percent: Int, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier
            .pixelSurface(MillidaPixel.counter, MillidaCut.badge, PixelFrame(bottom = MillidaPixel.pressBottom, bottomWidth = 2.dp))
            .padding(horizontal = if (big) 8.dp else 6.dp, vertical = 3.dp),
    ) {
        Text(
            "−$percent%",
            style = MillidaType.tabLabel.copy(fontWeight = FontWeight.Black, fontSize = if (big) 14.sp else 11.5.sp),
            color = MillidaPixel.white,
            maxLines = 1,
        )
    }
}
