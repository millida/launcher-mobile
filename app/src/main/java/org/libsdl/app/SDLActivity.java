package org.libsdl.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.LocaleList;
import android.view.Surface;

import androidx.annotation.Keep;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The Java half SDL3 binds to by name when its library is loaded. Not an
 * Activity: the game screen is ours and SDL only borrows its surface, so every
 * callback below answers from the {@link Host} the launcher installs, and the
 * ones that would reshape the screen or open dialogs answer "not handled".
 * Names and signatures are a binary contract with libSDL3.so of the pinned
 * tag; scripts/check_sdl_glue.py compares them with its JNI tables.
 */
@Keep
public final class SDLActivity {

    public interface Host {
        Activity activity();

        Surface surface();

        void relativeMouse(boolean enabled);

        void windowOpened();
    }

    private static volatile Host host = null;
    private static final Executor replies = Executors.newSingleThreadExecutor();

    private SDLActivity() {
    }

    public static void install(Host value) {
        host = value;
    }

    public static native String nativeGetVersion();

    public static native void nativeSetupJNI();

    public static native void nativeInitMainThread();

    public static native void nativeCleanupMainThread();

    public static native int nativeRunMain(String library, String function, Object arguments);

    public static native void onNativeDropFile(String filename);

    public static native void nativeSetScreenResolution(int surfaceWidth, int surfaceHeight, int deviceWidth, int deviceHeight, float density, float rate);

    public static native void onNativeResize();

    public static native void onNativeSurfaceCreated();

    public static native void onNativeSurfaceChanged();

    public static native void onNativeSurfaceDestroyed();

    public static native void onNativeScreenKeyboardShown();

    public static native void onNativeScreenKeyboardHidden();

    public static native void onNativeKeyDown(int keycode);

    public static native void onNativeKeyUp(int keycode);

    public static native boolean onNativeSoftReturnKey();

    public static native void onNativeKeyboardFocusLost();

    public static native void onNativeTouch(int touchDeviceId, int pointerFingerId, int action, float x, float y, float pressure);

    public static native void onNativePinchStart();

    public static native void onNativePinchUpdate(float scale);

    public static native void onNativePinchEnd();

    public static native void onNativeMouse(int button, int action, float x, float y, boolean relative);

    public static native void onNativePen(int penId, int deviceType, int button, int action, float x, float y, float pressure);

    public static native void onNativeAccel(float x, float y, float z);

    public static native void onNativeClipboardChanged();

    public static native void nativeLowMemory();

    public static native void onNativeLocaleChanged();

    public static native void onNativeDarkModeChanged(boolean enabled);

    public static native void nativeSendQuit();

    public static native void nativeQuit();

    public static native void nativePause();

    public static native void nativeResume();

    public static native void nativeFocusChanged(boolean hasFocus);

    public static native String nativeGetHint(String name);

    public static native boolean nativeGetHintBoolean(String name, boolean defaultValue);

    public static native void nativeSetenv(String name, String value);

    public static native void nativeSetNaturalOrientation(int orientation);

    public static native void onNativeRotationChanged(int rotation);

    public static native void onNativeInsetsChanged(int left, int right, int top, int bottom);

    public static native void nativeAddTouch(int touchId, String name);

    public static native void nativePermissionResult(int requestCode, boolean result);

    public static native boolean nativeAllowRecreateActivity();

    public static native int nativeCheckSDLThreadCounter();

    public static native void onNativeFileDialog(int requestCode, String[] fileList, int filter);

    public static Activity getContext() {
        final Host current = host;
        return current == null ? null : current.activity();
    }

    public static Surface getNativeSurface() {
        final Host current = host;
        if (current == null) return null;
        final Surface surface = current.surface();
        if (surface != null && surface.isValid()) current.windowOpened();
        return surface;
    }

    public static boolean getManifestEnvironmentVariables() {
        return false;
    }

    public static void initTouch() {
    }

    public static boolean isAndroidTV() {
        return false;
    }

    public static boolean isChromebook() {
        return false;
    }

    public static boolean isDeXMode() {
        return false;
    }

    public static boolean isTablet() {
        return false;
    }

    public static void manualBackButton() {
    }

    public static void minimizeWindow() {
    }

    public static boolean shouldMinimizeOnFocusLoss() {
        return false;
    }

    public static boolean setActivityTitle(String title) {
        return true;
    }

    public static void setWindowStyle(boolean fullscreen) {
    }

    public static void setOrientation(int width, int height, boolean resizable, String hint) {
    }

    public static boolean supportsRelativeMouse() {
        return true;
    }

    /** The game took or released the cursor; the launcher switches touch between camera and pointer. */
    public static boolean setRelativeMouseEnabled(boolean enabled) {
        final Host current = host;
        if (current != null) current.relativeMouse(enabled);
        return true;
    }

    /** The on-screen controls carry their own text field; the system keyboard is not raised behind the game's back. */
    public static boolean showTextInput(int x, int y, int width, int height, int inputType) {
        return true;
    }

    public static boolean sendMessage(int command, int param) {
        return false;
    }

    public static boolean showToast(String message, int duration, int gravity, int xOffset, int yOffset) {
        return false;
    }

    public static int createCustomCursor(int[] colors, int width, int height, int hotSpotX, int hotSpotY) {
        return 0;
    }

    public static void destroyCustomCursor(int cursorID) {
    }

    public static boolean setCustomCursor(int cursorID) {
        return false;
    }

    public static boolean setSystemCursor(int cursorID) {
        return false;
    }

    /** Answered from another thread: SDL waits for the result outside of this call. */
    public static void requestPermission(String permission, int requestCode) {
        replies.execute(() -> nativePermissionResult(requestCode, false));
    }

    public static int openFileDescriptor(String uri, String mode) {
        return -1;
    }

    public static boolean showFileDialog(String[] filters, boolean allowMultiple, boolean forWrite, int requestCode) {
        return false;
    }

    public static String getPreferredLocales() {
        final StringBuilder out = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final LocaleList locales = LocaleList.getAdjustedDefault();
            for (int index = 0; index < locales.size(); index++) {
                if (index > 0) out.append(',');
                append(out, locales.get(index));
            }
        } else {
            append(out, Locale.getDefault());
        }
        return out.toString();
    }

    private static void append(StringBuilder out, Locale locale) {
        out.append(locale.getLanguage());
        if (!locale.getCountry().isEmpty()) out.append('_').append(locale.getCountry());
    }

    public static boolean openURL(String url) {
        final Activity activity = getContext();
        if (activity == null || url == null || url.isEmpty()) return false;
        final Uri uri = Uri.parse(url);
        final String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static String clipboardGetText() {
        final ClipboardManager manager = clipboard();
        if (manager == null || !manager.hasPrimaryClip()) return null;
        final ClipData data = manager.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return null;
        final CharSequence text = data.getItemAt(0).getText();
        return text == null ? null : text.toString();
    }

    public static boolean clipboardHasText() {
        final ClipboardManager manager = clipboard();
        if (manager == null || !manager.hasPrimaryClip()) return false;
        final ClipDescription description = manager.getPrimaryClipDescription();
        return description != null && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
    }

    public static void clipboardSetText(String string) {
        final ClipboardManager manager = clipboard();
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Millida", string));
    }

    private static ClipboardManager clipboard() {
        final Activity activity = getContext();
        if (activity == null) return null;
        return (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    }
}
