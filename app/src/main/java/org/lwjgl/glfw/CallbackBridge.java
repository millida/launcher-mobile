package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Keep;

/**
 * Launcher half of the ready-made input bridge. The class name, the method names
 * and their signatures are a binary contract with the native library: it looks
 * them up by name while loading, and a single mismatch aborts the process. The
 * list below is taken from the library's own symbols and from the launcher the
 * runtime pack comes from.
 */
@Keep
public final class CallbackBridge {

    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static final int GLFW_PRESS = 1;
    public static final int GLFW_RELEASE = 0;
    public static final int GLFW_REPEAT = 2;
    public static final int GLFW_FOCUSED = 0x20001;
    public static final int GLFW_HOVERED = 0x2000B;

    public static volatile int windowWidth = 0;
    public static volatile int windowHeight = 0;
    public static volatile int physicalWidth = 0;
    public static volatile int physicalHeight = 0;

    public static volatile float mouseX = 0f;
    public static volatile float mouseY = 0f;

    public static volatile boolean holdingAlt = false;
    public static volatile boolean holdingCapslock = false;
    public static volatile boolean holdingCtrl = false;
    public static volatile boolean holdingNumlock = false;
    public static volatile boolean holdingShift = false;

    // The names of these fields are read by the native side, so they match the
    // launcher this window layer comes from, letter for letter.
    private static volatile boolean isGrabbing = false;
    private static volatile int cursorShape = 0;
    private static volatile Context context = null;
    private static volatile GrabListener grabListener = null;

    private CallbackBridge() {
    }

    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    private static native boolean nativeSendChar(char codepoint);

    private static native boolean nativeSendCharMods(char codepoint, int mods);

    private static native void nativeSendKey(int key, int scancode, int action, int mods);

    private static native void nativeSendCursorPos(float x, float y);

    private static native void nativeSendMouseButton(int button, int action, int mods);

    private static native void nativeSendScroll(double xoffset, double yoffset);

    private static native void nativeSendScreenSize(int width, int height);

    public static native void nativeSetWindowAttrib(int attrib, int value);

    public static native int getCurrentFps();

    /** The clipboard lives on the launcher side; the game asks for it through here. */
    @Keep
    public static String accessAndroidClipboard(int action, String copy) {
        final Context current = context;
        if (current == null) return "";
        switch (action) {
            case CLIPBOARD_COPY: {
                final ClipboardManager manager =
                    (ClipboardManager) current.getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Millida", copy));
                return null;
            }
            case CLIPBOARD_PASTE: {
                final ClipboardManager manager =
                    (ClipboardManager) current.getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager == null || !manager.hasPrimaryClip()) return "";
                final ClipDescription description = manager.getPrimaryClipDescription();
                if (description == null || !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return "";
                final ClipData data = manager.getPrimaryClip();
                if (data == null || data.getItemCount() == 0) return "";
                final CharSequence text = data.getItemAt(0).getText();
                return text == null ? "" : text.toString();
            }
            case CLIPBOARD_OPEN: {
                if (copy == null || copy.isEmpty()) return null;
                final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(copy));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    current.startActivity(intent);
                } catch (Exception ignored) {
                }
                return null;
            }
            default:
                return null;
        }
    }

    /** Called from the native side when the game takes or releases the cursor. */
    @Keep
    private static void onGrabStateChanged(final boolean grab) {
        isGrabbing = grab;
        final GrabListener listener = grabListener;
        if (listener != null) listener.onGrabState(grab);
    }

    /** Called from the native side when the game asks for a different cursor. */
    @Keep
    private static void onCursorShapeChanged(final int shape) {
        cursorShape = shape;
    }

    /** Called from the native side once per rendered frame. */
    @Keep
    private static void onGraphicOutput() {
    }

    public static void setContext(Context value) {
        context = value == null ? null : value.getApplicationContext();
    }

    public static void setGrabListener(GrabListener listener) {
        grabListener = listener;
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    public static int getCurrentMods() {
        int mods = 0;
        if (holdingShift) mods |= 1;
        if (holdingCtrl) mods |= 2;
        if (holdingAlt) mods |= 4;
        if (holdingCapslock) mods |= 16;
        if (holdingNumlock) mods |= 32;
        return mods;
    }

    public static void setModifiers(int keyCode, boolean pressed) {
        switch (keyCode) {
            case 340:
            case 344:
                holdingShift = pressed;
                break;
            case 341:
            case 345:
                holdingCtrl = pressed;
                break;
            case 342:
            case 346:
                holdingAlt = pressed;
                break;
            case 280:
                if (pressed) holdingCapslock = !holdingCapslock;
                break;
            case 282:
                if (pressed) holdingNumlock = !holdingNumlock;
                break;
            default:
                break;
        }
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean pressed) {
        setModifiers(keyCode, pressed);
        nativeSendKey(keyCode, scancode, pressed ? GLFW_PRESS : GLFW_RELEASE, modifiers);
    }

    public static void sendKeyRepeat(int keyCode, int scancode, int modifiers) {
        nativeSendKey(keyCode, scancode, GLFW_REPEAT, modifiers);
    }

    public static void sendChar(char codepoint, int modifiers) {
        if (!nativeSendCharMods(codepoint, modifiers)) {
            nativeSendChar(codepoint);
        }
    }

    public static void sendMouseButton(int button, boolean pressed) {
        nativeSendMouseButton(button, pressed ? GLFW_PRESS : GLFW_RELEASE, getCurrentMods());
    }

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(x, y);
    }

    public static void sendCursorDelta(float x, float y) {
        sendCursorPos(mouseX + x, mouseY + y);
    }

    public static void sendScroll(double horizontal, double vertical) {
        nativeSendScroll(horizontal, vertical);
    }

    public static void sendUpdateWindowSize(int width, int height) {
        windowWidth = width;
        windowHeight = height;
        physicalWidth = width;
        physicalHeight = height;
        nativeSendScreenSize(width, height);
    }

    @Keep
    public interface GrabListener {
        void onGrabState(boolean grabbing);
    }
}
