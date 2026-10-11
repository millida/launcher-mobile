package org.libsdl.app;

import androidx.annotation.Keep;

/** Text typed for the game on the SDL3 path. Binary contract with libSDL3.so, see {@link SDLActivity}. */
@Keep
public final class SDLInputConnection {

    private SDLInputConnection() {
    }

    public static native void nativeCommitText(String text, int newCursorPosition);

    public static native void nativeGenerateScancodeForUnichar(char c);
}
