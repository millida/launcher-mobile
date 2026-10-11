package org.libsdl.app;

import androidx.annotation.Keep;

/**
 * Declared only so SDL3 can register its natives while loading: a class it
 * cannot find leaves an exception pending inside its JNI_OnLoad. HIDAPI is
 * switched off for the game, so no instance is ever made. Binary contract,
 * see {@link SDLActivity}.
 */
@Keep
public final class HIDDeviceManager {

    private HIDDeviceManager() {
    }

    private native void HIDDeviceRegisterCallback();

    private native void HIDDeviceReleaseCallback();

    native void HIDDeviceConnected(int deviceId, String identifier, int vendorId, int productId, String serialNumber, int releaseNumber, String manufacturer, String product, int interfaceNumber, int interfaceClass, int interfaceSubclass, int interfaceProtocol, boolean bluetooth, int reportId);

    native void HIDDeviceOpenPending(int deviceId);

    native void HIDDeviceOpenResult(int deviceId, boolean opened);

    native void HIDDeviceDisconnected(int deviceId);

    native void HIDDeviceInputReport(int deviceId, byte[] report);

    native void HIDDeviceReportResponse(int deviceId, byte[] report);
}
