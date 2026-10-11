package com.movtery.zalithlauncher.bridge;

import androidx.annotation.Keep;

/**
 * The launcher side of the ready-made runtime bridge. The names of this class,
 * its package and every method below are part of a binary contract: the native
 * library exports its entry points as Java_com_movtery_zalithlauncher_bridge_ZLBridge_*,
 * so nothing here can be renamed or moved.
 */
@Keep
public final class ZLBridge {

    private ZLBridge() {
    }

    public static native void initializeGameExitHook();

    public static native void setupExitMethod(android.content.Context context);

    public static native void setLdLibraryPath(String ldLibraryPath);

    public static native boolean dlopen(String libPath);

    public static native void setupBridgeWindow(Object surface);

    public static native void releaseBridgeWindow();

    public static native void moveWindow(int xOffset, int yOffset);

    public static native int[] renderAWTScreenFrame();

    public static native void sendInputData(int type, int i1, int i2, int i3, int i4);

    public static native void clipboardReceived(String data, String mimeTypeSub);

    public static native int chdir(String path);
}
