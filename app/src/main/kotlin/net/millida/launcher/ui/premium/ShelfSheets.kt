package net.millida.launcher.ui.premium

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.UiState
import net.millida.launcher.ui.catalog.MillidaDetailSheet
import net.millida.launcher.ui.catalog.MillidaShelfModel

/**
 * The catalogue's pack card shown over the screen that opened it, so a pack
 * from the lobby or the showcase installs without leaving it. Purchase, key
 * and phone refusal windows come from the shared MillidaDialogsHost.
 */
@Composable
internal fun MillidaShelfSheets(state: UiState, model: LauncherViewModel) {
    val shelf = model.millidaShelf
    val ms = shelf.state.collectAsStateWithLifecycle().value
    val cs = model.catalogModel.state.collectAsStateWithLifecycle().value

    ms.detail?.let { detail ->
        MillidaDetailSheet(
            detail = detail,
            bought = ms.access[detail.slug] == true,
            waiting = detail.slug in ms.waiting,
            signedIn = state.millidaSignedIn,
            job = cs.modpack?.takeIf { it.key == MillidaShelfModel.key(detail.slug) },
            busy = cs.modpack != null,
            onDismiss = shelf::closeDetail,
            onPrimary = shelf::primary,
            onKey = { shelf.openKey(detail.slug, detail.title, "") },
            onRetry = shelf::retryDetail,
            onSite = { shelf.openOnSite(MillidaShelfModel.SECTION, detail.slug) },
        )
    }
}
