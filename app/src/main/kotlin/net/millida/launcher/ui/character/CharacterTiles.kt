package net.millida.launcher.ui.character

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import net.millida.launcher.core.skins.CapeOffer
import net.millida.launcher.core.skins.Reward
import net.millida.launcher.ui.CapeFigure
import net.millida.launcher.ui.MCard
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MProgressBar
import net.millida.launcher.ui.MRarityFrame
import net.millida.launcher.ui.MSkeleton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface

internal val TILE_ART_HEIGHT: Dp = 128.dp
private val TILE_HEIGHT: Dp = 190.dp

internal enum class TileMark { None, Worn, Trying }

@Composable
internal fun ItemTile(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rarity: Rarity? = null,
    mark: TileMark = TileMark.None,
    note: String? = null,
    locked: Boolean = false,
    onRemove: (() -> Unit)? = null,
    toolIcon: MillidaIcon = MIcons.trash,
    toolLabel: String = "Удалить",
    art: @Composable () -> Unit,
) {
    val colors = millida
    val status: Pair<String, Color>? = when (mark) {
        TileMark.Worn -> "Надето" to colors.accentText
        TileMark.Trying -> "Примерка" to colors.info
        TileMark.None -> note?.let { it to colors.fgFaint }
    }
    MRarityFrame(
        rarity = rarity,
        modifier = modifier.fillMaxWidth(),
        selected = mark == TileMark.Trying,
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TILE_ART_HEIGHT),
            ) {
                art()
            }
            Spacer(Modifier.height(8.dp))
            Text(
                name,
                style = MillidaType.rowTitle.copy(fontSize = 14.sp),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .heightIn(min = 19.dp)
                    .padding(top = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (status != null) {
                    Text(
                        status.first,
                        style = MillidaType.small.copy(fontWeight = FontWeight.Bold),
                        color = status.second,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (locked) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(26.dp)
                    .pixelSurface(colors.inset, MillidaCut.badge),
                contentAlignment = Alignment.Center,
            ) {
                MIcon(MIcons.lock, size = 14.dp)
            }
        }
        if (onRemove != null) {
            MIconButton(
                toolIcon,
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
                background = colors.surface,
                label = toolLabel,
            )
        }
    }
}

@Composable
internal fun SkeletonTile(modifier: Modifier = Modifier) {
    MSkeleton(height = TILE_HEIGHT, modifier = modifier)
}

@Composable
internal fun ShelfTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MillidaType.sectionTitle,
            color = millida.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

@Composable
internal fun RewardCard(
    reward: Reward,
    trying: Boolean,
    onTry: () -> Unit,
    onClaim: () -> Job?,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    var claiming by remember(reward.code) { mutableStateOf(false) }
    val ready = reward.done && !reward.claimed
    MCard(modifier, background = if (trying) colors.accentToday else colors.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 60.dp, height = 84.dp)
                    .clickable(
                        enabled = reward.capeUrl.isNotEmpty(),
                        onClickLabel = "Примерить плащ",
                        role = Role.Button,
                        onClick = onTry,
                    ),
            ) {
                CapeFigure(url = reward.capeUrl.ifEmpty { null }, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    reward.title.ifEmpty { reward.code },
                    style = MillidaType.rowTitle,
                    color = colors.fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val task = reward.task.ifEmpty { reward.hint }
                if (task.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        task,
                        style = MillidaType.small,
                        color = colors.fgSubtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (reward.goal > 0 && !reward.claimed) {
            Spacer(Modifier.height(12.dp))
            MProgressBar((reward.progress.toFloat() / reward.goal).coerceIn(0f, 1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    reward.claimed -> "Получено"
                    ready -> "Можно забрать"
                    reward.goal > 0 -> "${reward.progress} из ${reward.goal}"
                    else -> "В процессе"
                },
                modifier = Modifier.weight(1f),
                style = MillidaType.pixel,
                color = if (ready) colors.accentText else colors.fgFaint,
                maxLines = 1,
            )
            if (ready) {
                MPrimaryButton(
                    "Забрать",
                    onClick = {
                        claiming = true
                        val job = onClaim()
                        job?.invokeOnCompletion { claiming = false }
                        if (job == null) claiming = false
                    },
                    icon = MIcons.gift,
                    loading = claiming,
                )
            }
        }
    }
}

internal fun capeProgress(cape: CapeOffer): String = when {
    cape.progressTarget > 0 -> "${cape.progressCurrent} из ${cape.progressTarget} ${cape.progressUnit}".trim()
    cape.requirement.isNotEmpty() -> cape.requirement
    else -> "Пока закрыт"
}
