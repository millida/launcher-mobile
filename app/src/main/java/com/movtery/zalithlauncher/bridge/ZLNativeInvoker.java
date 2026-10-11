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
