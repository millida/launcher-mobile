package net.millida.launcher.ui.character

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import net.millida.launcher.UiState
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.cosmetics.CosmeticSections
import net.millida.launcher.core.cosmetics.Look
import net.millida.launcher.core.cosmetics.OutfitCape
import net.millida.launcher.core.cosmetics.OutfitSkin
import net.millida.launcher.core.cosmetics.WornCosmetic
import net.millida.launcher.core.skins.SkinCatalog
import net.millida.launcher.ui.MIconButton
import net.millida.launcher.ui.MIcons
import net.millida.launcher.ui.MPrimaryButton
import net.millida.launcher.ui.MSecondaryButton
import net.millida.launcher.ui.MillidaCut
import net.millida.launcher.ui.MillidaIcon
import net.millida.launcher.ui.MillidaType
import net.millida.launcher.ui.PixelCorners
import net.millida.launcher.ui.millida
import net.millida.launcher.ui.pixelSurface
import net.millida.launcher.ui.plateFrame

/** Puts the piece on for real; [done] runs only after the server took it. Null job: nothing was started. */
internal typealias Wear = (done: () -> Unit) -> Job?

internal enum class WearTarget { Licence, Millida, SignedOut }

/**
 * The wardrobe sections in the desktop's order (Skins.tsx sectionList): looks,
 * skins, capes, then one section per cosmetic part. [section] is the
 * [CosmeticSections] key a cosmetic tab shows.
 */
internal enum class CharacterTab(val label: String, val section: String? = null) {
    LOOKS("Образы"),
    SKIN("Скины"),
    CAPE("Плащи"),
    HEAD("Голова", "head"),
    BODY("Тело", "body"),
    BACK("Спина", "back"),
    PETS("Питомцы", "pet"),
    EMOTES("Эмоции", CosmeticSections.EMOTES),
    WARDROBE("Гардероб"),
    CATALOG("Каталог"),
    REWARDS("Награды"),
    IMPORT("Импорт"),
}

internal fun CharacterTab.icon(): MillidaIcon = when (this) {
    CharacterTab.SKIN -> MIcons.wardrobeSkin
    CharacterTab.CAPE -> MIcons.wardrobeCape
    CharacterTab.HEAD -> MIcons.wardrobeHead
    CharacterTab.BODY -> MIcons.wardrobeBody
    CharacterTab.BACK -> MIcons.wardrobeBack
    CharacterTab.PETS -> MIcons.wardrobePet
    CharacterTab.EMOTES -> MIcons.wardrobeEmote
    CharacterTab.LOOKS -> MIcons.looks
    CharacterTab.WARDROBE -> MIcons.wardrobeLooks
    CharacterTab.CATALOG -> MIcons.catalog
    CharacterTab.REWARDS -> MIcons.trophy
    CharacterTab.IMPORT -> MIcons.upload
}

@Immutable
internal data class CharacterLook(
    val nick: String,
    val skinUrl: String?,
    val slim: Boolean,
    val capeUrl: String?,
)

internal fun wearTargetOf(state: UiState): WearTarget = when {
    state.account?.kind == AccountKind.MICROSOFT -> WearTarget.Licence
    state.millidaSignedIn -> WearTarget.Millida
    else -> WearTarget.SignedOut
}

internal fun characterLookOf(state: UiState, licence: LicenceState, target: WearTarget = wearTargetOf(state)): CharacterLook {
    val accountNick = state.account?.nick.orEmpty()
    if (target == WearTarget.Licence) {
        val profile = licence.profile?.takeIf { licence.accountId == state.account?.id }
        return CharacterLook(
            nick = profile?.nick?.ifEmpty { null } ?: accountNick,
            skinUrl = profile?.skinUrl?.ifEmpty { null },
            slim = profile?.slim ?: false,
            capeUrl = profile?.activeCape?.url?.ifEmpty { null },
        )
    }
    val profile = if (target == WearTarget.Millida) state.gameProfile else null
    return CharacterLook(
        nick = profile?.name?.ifEmpty { null } ?: accountNick,
        skinUrl = profile?.skinUrl?.ifEmpty { null } ?: nickSkinUrl(accountNick),
        slim = profile?.slim ?: false,
        capeUrl = profile?.capeUrl?.ifEmpty { null },
    )
}

/**
 * The head service resolves a nick to the launcher skin first, then the game's,
 * as the desktop lobby and the avatar do; the account may hold no skin of its
 * own, and the profile is still on its way on the first frames.
 */
private fun nickSkinUrl(nick: String): String =
    nick.trim().ifEmpty { null }?.let { SkinCatalog.skinUrl(Uri.encode(it)) } ?: SkinCatalog.DEFAULT_SKIN_URL

/**
 * What the Millida account wears now, as a look to save. The wardrobe cards are
 * remembered so a look goes back on by id instead of sending the picture again.
 */
internal fun lookOf(state: UiState, target: WearTarget, worn: List<WornCosmetic>, wornReady: Boolean): Look? {
    val profile = state.gameProfile ?: return null
    if (target != WearTarget.Millida || profile.skinUrl.isEmpty() || !wornReady) return null
    val skinCard = state.wardrobe.firstOrNull { !it.isCape && it.url == profile.skinUrl }
    val capeCard = state.wardrobe.firstOrNull { it.isCape && profile.capeUrl.isNotEmpty() && it.url == profile.capeUrl }
    return Look(
        skin = OutfitSkin(profile.skinUrl, profile.slim, skinCard?.id.orEmpty()),
        cape = OutfitCape(profile.capeUrl, capeCard?.id.orEmpty()),
        cosmetics = worn,
    )
}

@Immutable
internal class Fitting(
    val key: String,
    val title: String,
    val skinUrl: String? = null,
    val slim: Boolean? = null,
    val capeUrl: String? = null,
    val note: String? = null,
    val wear: Wear? = null,
    val save: (() -> Unit)? = null,
    val signIn: Boolean = false,
)

@Immutable
internal class ConfirmAsk(
    val title: String,
    val text: String?,
    val action: String,
    val run: () -> Unit,
)

@Composable
internal fun FittingBar(
    fitting: Fitting,
    wearing: Boolean,
    onCancel: () -> Unit,
    onWear: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = millida
    Column(
        modifier
            .fillMaxWidth()
            .pixelSurface(
                fill = colors.raised,
                cut = MillidaCut.plate,
                frame = colors.plateFrame().copy(outline = colors.dockOutline),
                corners = PixelCorners.Top,
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text("ПРИМЕРКА", style = MillidaType.caption, color = colors.info, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            fitting.title,
            style = MillidaType.rowTitle,
            color = colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (fitting.note != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                fitting.note,
                style = MillidaType.small,
                color = colors.fgSubtle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MSecondaryButton("Отмена", onClick = onCancel, modifier = Modifier.weight(1f), enabled = !wearing)
            when {
                fitting.wear != null -> MPrimaryButton(
                    "Надеть",
                    onClick = onWear,
                    modifier = Modifier.weight(1f),
                    icon = MIcons.check,
                    loading = wearing,
                )
                fitting.signIn -> MPrimaryButton(
                    "Войти",
                    onClick = onSignIn,
                    modifier = Modifier.weight(1f),
                    icon = MIcons.login,
                )
            }
            val save = fitting.save
            if (save != null) {
                MIconButton(MIcons.download, onClick = save, label = "В библиотеку", enabled = !wearing)
            }
        }
    }
}
