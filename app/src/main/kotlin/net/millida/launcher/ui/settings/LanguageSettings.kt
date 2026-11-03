package net.millida.launcher.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.AppLanguage
import net.millida.launcher.R
import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.LocaleInfo
import net.millida.launcher.core.i18n.Locales
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.media.UiSoundEvent
import net.millida.launcher.ui.LocalWideLayout
import net.millida.launcher.ui.MBottomSheet
import net.millida.launcher.ui.MIcon
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MListRow
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelFrame
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.pressShift
import net.millida.launcher.ui.rememberPressProgress
import net.millida.launcher.ui.rememberPressed

/** Twemoji flag of each language, as on the site's picker. English shows the US flag and Telugu shares India's with Hindi. */
@DrawableRes
internal fun languageFlag(code: String): Int? = when (code) {
    "ru" -> R.drawable.flag_ru
    "en" -> R.drawable.flag_us
    "zh" -> R.drawable.flag_cn
    "hi", "te" -> R.drawable.flag_in
    "es" -> R.drawable.flag_es
    "ar" -> R.drawable.flag_sa
    "fr" -> R.drawable.flag_fr
    "bn" -> R.drawable.flag_bd
    "pt-BR" -> R.drawable.flag_br
    "id" -> R.drawable.flag_id
    "ur" -> R.drawable.flag_pk
    "de" -> R.drawable.flag_de
    "ja" -> R.drawable.flag_jp
    "vi" -> R.drawable.flag_vn
    "tr" -> R.drawable.flag_tr
    "ko" -> R.drawable.flag_kr
    "pl" -> R.drawable.flag_pl
    "uk" -> R.drawable.flag_ua
    "sr" -> R.drawable.flag_rs
    "sk" -> R.drawable.flag_sk
    else -> null
}

private const val TABLET_MIN_WIDTH_DP = 600

private val TILE_HEIGHT = 48.dp

private val TILE_GAP = 6.dp

/** Settings → Вид → «Язык интерфейса» (locale-picker.tsx on the site): every language at once, each under its own name. */
@Composable
internal fun LanguagePicker(modifier: Modifier = Modifier) {
    val activity = LocalContext.current.activity()
    val current by I18n.locale.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val info = Locales.info(current)
    MListRow(
        title = info.native,
        subtitle = englishOf(info),
        onClick = { open = true },
        modifier = modifier,
        trailing = { MIcon(MIcons.chevronDown, size = 16.dp, color = millida.fgFaint) },
    )
    if (open) {
        MBottomSheet(onDismiss = { open = false }, title = tr("Язык интерфейса")) {
            LanguageGrid(current) { code ->
                open = false
                if (activity != null) AppLanguage.choose(activity, code)
            }
        }
    }
}

@Composable
private fun LanguageGrid(current: String, onPick: (String) -> Unit) {
    val columns = if (LocalWideLayout.current || LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP) 4 else 2
    Column(
        Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        Locales.AVAILABLE_LOCALES.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                row.forEach { option ->
                    LanguageTile(option, option.code == current, Modifier.weight(1f)) { onPick(option.code) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LanguageTile(option: LocaleInfo, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = millida
    val interaction = remember { MutableInteractionSource() }
    val pressed by rememberPressed(interaction, UiSoundEvent.Click)
    val progress = rememberPressProgress(pressed)
    val fill = when {
        chosen -> colors.accentSoft
        pressed -> colors.segmentFillOn
        else -> colors.chipFill
    }
    val english = englishOf(option)
    Row(
        modifier
            .pressShift(progress, 1.dp)
            .height(TILE_HEIGHT)
            .pixelSurface(fill, MillidaCut.control, if (chosen) PixelFrame.Chip else PixelFrame.None)
            .selectable(chosen, interaction, null, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { if (english != null) contentDescription = "${option.native}, $english" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        languageFlag(option.code)?.let { flag ->
            Image(painterResource(flag), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            option.native.uppercase(Locales.javaLocale(option.code)),
            modifier = Modifier.weight(1f),
            style = MillidaType.button.copy(
                fontSize = 13.sp,
                fontWeight = if (chosen) FontWeight.ExtraBold else FontWeight.Bold,
            ),
            color = if (chosen) colors.accentText else colors.fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun englishOf(info: LocaleInfo): String? = info.english.takeIf { it != info.native }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
