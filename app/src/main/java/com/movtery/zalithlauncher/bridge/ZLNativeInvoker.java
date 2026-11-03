package com.movtery.zalithlauncher.bridge;

import androidx.annotation.Keep;

/**
 * Calls the native side makes back into the launcher outside the input path.
 * Looked up by name at the moment of the call, so the names stay as they are.
 */
@Keep
public final class ZLNativeInvoker {

    private ZLNativeInvoker() {
    }

    /**
     * The Java machine exited or aborted. The native side looks this method up when the
     * game starts and calls it from the thread that ended the game; without it an abort
     * found nobody to report to and left the game hanging with a black picture.
     */
    @Keep
    public static void jvmExit(int exitCode, boolean isSignal) {
        net.millida.launcher.ExitTrap.trapped(exitCode, isSignal);
    }

    @Keep
    public static void openLink(String link) {
        org.lwjgl.glfw.CallbackBridge.accessAndroidClipboard(
            org.lwjgl.glfw.CallbackBridge.CLIPBOARD_OPEN, link);
    }

    @Keep
    public static void putClipboardData(String data) {
        org.lwjgl.glfw.CallbackBridge.accessAndroidClipboard(
            org.lwjgl.glfw.CallbackBridge.CLIPBOARD_COPY, data);
    }

    @Keep
    public static String querySystemClipboard() {
        return org.lwjgl.glfw.CallbackBridge.accessAndroidClipboard(
            org.lwjgl.glfw.CallbackBridge.CLIPBOARD_PASTE, null);
    }
}
