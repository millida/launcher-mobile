package net.millida.launcher.ui.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.premium.Premium
import net.millida.launcher.core.premium.PremiumMore
import net.millida.launcher.core.premium.PremiumPack
import net.millida.launcher.core.premium.PremiumTier
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MRemoteImage
import net.millida.launcher.ui.MillidaPixel
import net.millida.launcher.ui.MillidaShape
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.millida

private val SCROLL_CARD_WIDTH = 210.dp

/** The desktop's «Ещё популярные премиум-сборки»: three best sellers in a row, the open one left out. */
@Composable
internal fun PremiumMoreRow(
    packs: List<PremiumPack>,
    current: String?,
    plan: PremiumTier?,
    onOpen: (PremiumPack) -> Unit,
    fill: Boolean,
) {
    val colors = millida
    val list = PremiumMore.pick(packs, current)
    if (list.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(MIcons.crown, size = 18.dp, color = colors.warning)
            Spacer(Modifier.width(8.dp))
            Text(tr("Ещё популярные премиум-сборки"), style = MillidaType.sectionTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        if (fill) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { pack -> MoreCard(pack, plan, { onOpen(pack) }, Modifier.weight(1f)) }
            }
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                list.forEach { pack -> MoreCard(pack, plan, { onOpen(pack) }, Modifier.width(SCROLL_CARD_WIDTH)) }
            }
        }
    }
}

@Composable
private fun MoreCard(pack: PremiumPack, plan: PremiumTier?, onOpen: () -> Unit, modifier: Modifier) {
    val colors = millida
    val own = PremiumMore.isOwn(pack)
    val price = Premium.priceLabel(pack, plan)
    MCard(modifier = modifier, onClick = onOpen, background = colors.surface2, contentPadding = 8.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            MRemoteImage(pack.coverUrl, MIcons.crown, Modifier.fillMaxSize(), shape = MillidaShape.control)
            if (!own) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(MillidaPixel.modalScrim, MillidaShape.control)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MIcon(MIcons.check, size = 12.dp, color = MillidaPixel.white)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Полностью на русском"), style = MillidaType.small, color = MillidaPixel.white, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(pack.title, style = MillidaType.rowTitle, color = colors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (own) tr("Своя сборка студии") else tr("Перевод, настройка и запуск в 1 клик"),
            style = MillidaType.small,
            color = colors.fgSubtle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (price.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(price, style = MillidaType.pixel, color = colors.accentText, maxLines = 1)
        }
    }
}
