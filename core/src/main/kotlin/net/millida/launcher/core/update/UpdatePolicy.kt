package net.millida.launcher.core.update

/** What to do with a downloaded, verified package right now. */
enum class InstallMoment {
    /** Hand the package to the installer. */
    Install,

    /** The game process is alive: replacing the app would kill it, so wait for its exit or the next start. */
    WaitForGame,

    /** Nothing to install. */
    Nothing,
}

object UpdatePolicy {

    /** Packages below this size are fetched in the background even over a metered network. */
    const val SMALL_UPDATE_BYTES: Long = 30L * 1024 * 1024

    /**
     * Background download without a tap: always on an unmetered network, on a
     * metered one only when the package is known to be small. An unknown size
     * counts as large. A release the player postponed is not fetched behind
     * their back.
     */
    fun shouldDownloadInBackground(
        sizeBytes: Long,
        unmetered: Boolean,
        postponed: Boolean = false,
    ): Boolean {
        if (postponed) return false
        if (unmetered) return true
        return sizeBytes in 1 until SMALL_UPDATE_BYTES
    }

    /**
     * Installing replaces the running app, so it never happens while the game
     * process lives: the game would be killed mid-session. The decision is
     * re-asked on game exit and on the next launcher start.
     */
    fun installMoment(packageReady: Boolean, gameAlive: Boolean): InstallMoment = when {
        !packageReady -> InstallMoment.Nothing
        gameAlive -> InstallMoment.WaitForGame
        else -> InstallMoment.Install
    }

    /**
     * USER_ACTION_NOT_REQUIRED exists from API 31 (S); below it the system
     * always asks, and the flag must not be touched.
     */
    fun canSkipUserAction(sdk: Int): Boolean = sdk >= 31

    /** The system honours "no user action" only for an installer that declares this permission. */
    const val SILENT_UPDATE_PERMISSION = "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"

    /** Whether the system will install the update without asking the player. */
    fun installsSilently(sdk: Int, declaredPermissions: Collection<String>): Boolean =
        canSkipUserAction(sdk) && SILENT_UPDATE_PERMISSION in declaredPermissions

    /**
     * An install the system will ask about is started by the player's own tap only: its
     * confirmation must not open by itself over whatever he is doing. The download that
     * ran in the background then waits for that tap, and the tap installs at once.
     */
    fun startsInstall(manual: Boolean, silent: Boolean): Boolean = manual || silent
}
