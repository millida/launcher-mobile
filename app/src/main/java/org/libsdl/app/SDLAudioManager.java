package org.libsdl.app;

import androidx.annotation.Keep;

/**
 * SDL3 audio is never opened by the games on this path: sound goes through
 * OpenAL. The class exists because SDL refuses to start until all three of
 * its Java halves have set themselves up. Binary contract, see {@link SDLActivity}.
 */
@Keep
public final class SDLAudioManager {

    private SDLAudioManager() {
    }

    public static native void nativeSetupJNI();

    public static native void nativeAddAudioDevice(boolean recording, String name, int deviceId);

    public static native void nativeRemoveAudioDevice(boolean recording, int deviceId);

    public static void registerAudioDeviceCallback() {
    }

    public static void unregisterAudioDeviceCallback() {
    }

    public static void audioSetThreadPriority(boolean recording, int deviceId) {
    }
}
