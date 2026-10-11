package net.millida.launcher.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.Screen
import net.millida.launcher.UiState

/** The welcome splash and the lobby guide, above the screens and below the first-run pages. */
@Composable
fun AmbientLayer(state: UiState, model: LauncherViewModel) {
    val ambient by model.ambient.state.collectAsStateWithLifecycle()
    val account = state.account
    LaunchedEffect(state.onboarding, account?.id) {
        when {
            state.onboarding -> model.ambient.skipWelcome()
            account != null -> model.ambient.askWelcome(state.gameProfile?.name?.takeIf { it.isNotBlank() } ?: account.nick)
        }
    }
    if (state.onboarding) return
    ambient.welcome?.let { ask -> WelcomeSplash(ask, onDone = model.ambient::closeWelcome) }
    val step = ambient.tourStep
    if (step != null && ambient.welcome == null && state.screen == Screen.HOME) {
        LobbyTour(
            step = step,
            hasBuilds = state.profiles.isNotEmpty(),
            onStep = model.ambient::tourStep,
            onClose = model.ambient::closeTour,
        )
    }
}
