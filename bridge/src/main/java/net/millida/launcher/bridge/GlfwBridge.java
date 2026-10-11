package net.millida.launcher.bridge;

import java.io.File;

/**
 * Java side of the native runtime bridge.
 *
 * The desktop virtual machine resolves native methods only among libraries it
 * loaded itself, so the library is claimed here even though the launcher process
 * already has it open. It is looked up by name first and by absolute path
 * second: whether the library exists as a file at all depends on how the
 * application was packaged, and the launcher passes the path it knows.
 */
public final class GlfwBridge {

    public static final int EVENT_CURSOR = 1;
    public static final int EVENT_MOUSE_BUTTON = 2;
    public static final int EVENT_KEY = 3;
    public static final int EVENT_CHAR = 4;
    public static final int EVENT_SCROLL = 5;
    public static final int EVENT_WINDOW_SIZE = 6;
    public static final int EVENT_FOCUS = 7;
    public static final int EVENT_CURSOR_DELTA = 8;
    public static final int EVENT_CLOSE = 9;

    private static final String LIBRARY = "millida_bridge";
    private static final String PATH_PROPERTY = "millida.bridge.lib";
    private static final String PATH_ENVIRONMENT = "MILLIDA_BRIDGE_LIB";

    static {
        load();
    }

    private GlfwBridge() {
    }

    private static void load() {
        Throwable byName = null;
        try {
            System.loadLibrary(LIBRARY);
            return;
        } catch (Throwable failure) {
            byName = failure;
        }

        final String path = firstExisting(
            System.getProperty(PATH_PROPERTY),
            System.getenv(PATH_ENVIRONMENT));
        if (path != null) {
            System.load(path);
            return;
        }

        throw new UnsatisfiedLinkError(
            "не загрузился мост " + LIBRARY + ": " + byName
                + "; путь из " + PATH_PROPERTY + " тоже не подошёл");
    }

    private static String firstExisting(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            if (new File(candidate).isFile()) return candidate;
        }
        return null;
    }

    public static native boolean setupWindow(String rendererDir, String rendererLibrary, String eglLibrary);

    public static native boolean makeCurrent();

    public static native boolean swapBuffers();

    public static native void setSwapInterval(int interval);

    public static native boolean pollEvent(int[] out);

    public static native int windowWidth();

    public static native int windowHeight();

    public static native long procAddress(String name);

    public static String rendererDir() {
        return orEmpty(System.getenv("MILLIDA_RENDERER_DIR"));
    }

    public static String rendererLibrary() {
        return orEmpty(System.getenv("MILLIDA_RENDERER_LIB"));
    }

    public static String eglLibrary() {
        return orEmpty(System.getenv("MILLIDA_EGL_LIB"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
