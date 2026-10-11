package com.oracle.dalvik;

import androidx.annotation.Keep;

/**
 * Starts the desktop virtual machine inside this process. The first argument is
 * the path to the java binary, the rest are its options, the main class and the
 * game arguments. The call blocks until the game exits and returns its code.
 */
@Keep
public final class VMLauncher {

    private VMLauncher() {
    }

    public static native int launchJVM(String[] args);
}
