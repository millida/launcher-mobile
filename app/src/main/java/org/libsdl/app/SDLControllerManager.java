package org.libsdl.app;

import androidx.annotation.Keep;

/**
 * Gamepads reach the game through the launcher's own input layer, the same
 * one the GLFW path uses, so SDL3 is told there are none: its list stays
 * empty and a pad never acts twice. Binary contract, see {@link SDLActivity}.
 */
@Keep
public final class SDLControllerManager {

    private SDLControllerManager() {
    }

    public static native void nativeSetupJNI();

    public static native boolean onNativePadDown(int deviceId, int keycode, int scancode);

    public static native boolean onNativePadUp(int deviceId, int keycode, int scancode);

    public static native void onNativeJoy(int deviceId, int axis, float value);

    public static native void onNativeHat(int deviceId, int hatId, int x, int y);

    public static native void onNativeJoySensor(int deviceId, int sensorType, long sensorTimestamp, float x, float y, float z);

    public static native void nativeAddJoystick(int deviceId, String name, String desc, int vendorId, int productId, int buttonMask, int naxes, int axisMask, int nhats, boolean canRumble, boolean hasRgbLed, boolean hasAccelerometer, boolean hasGyroscope);

    public static native void nativeRemoveJoystick(int deviceId);

    public static native void nativeAddHaptic(int deviceId, String name);

    public static native void nativeRemoveHaptic(int deviceId);

    public static void pollInputDevices() {
    }

    public static void pollHapticDevices() {
    }

    public static void joystickSetLED(int deviceId, int red, int green, int blue) {
    }

    public static void joystickSetSensorsEnabled(int deviceId, boolean enabled) {
    }

    public static void hapticRun(int deviceId, float intensity, int length) {
    }

    public static void hapticRumble(int deviceId, float lowFrequencyIntensity, float highFrequencyIntensity, int length) {
    }

    public static void hapticStop(int deviceId) {
    }
}
