package org.lwjgl.glfw;

import net.millida.launcher.bridge.GlfwBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.FunctionProvider;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Replacement for the desktop GLFW bindings. The game keeps calling the same
 * API; the window, the context and the input queue come from the launcher's
 * native bridge instead of a desktop window manager.
 */
public final class GLFW {

    public static final int GLFW_FALSE = 0;
    public static final int GLFW_TRUE = 1;
    public static final int GLFW_DONT_CARE = -1;

    public static final int GLFW_RELEASE = 0;
    public static final int GLFW_PRESS = 1;
    public static final int GLFW_REPEAT = 2;

    public static final int GLFW_NOT_INITIALIZED = 0x10001;
    public static final int GLFW_NO_CURRENT_CONTEXT = 0x10002;
    public static final int GLFW_INVALID_ENUM = 0x10003;
    public static final int GLFW_INVALID_VALUE = 0x10004;
    public static final int GLFW_OUT_OF_MEMORY = 0x10005;
    public static final int GLFW_API_UNAVAILABLE = 0x10006;
    public static final int GLFW_VERSION_UNAVAILABLE = 0x10007;
    public static final int GLFW_PLATFORM_ERROR = 0x10008;
    public static final int GLFW_FORMAT_UNAVAILABLE = 0x10009;
    public static final int GLFW_NO_WINDOW_CONTEXT = 0x1000A;

    public static final int GLFW_FOCUSED = 0x20001;
    public static final int GLFW_ICONIFIED = 0x20002;
    public static final int GLFW_RESIZABLE = 0x20003;
    public static final int GLFW_VISIBLE = 0x20004;
    public static final int GLFW_DECORATED = 0x20005;
    public static final int GLFW_AUTO_ICONIFY = 0x20006;
    public static final int GLFW_FLOATING = 0x20007;
    public static final int GLFW_MAXIMIZED = 0x20008;
    public static final int GLFW_CENTER_CURSOR = 0x20009;
    public static final int GLFW_TRANSPARENT_FRAMEBUFFER = 0x2000A;
    public static final int GLFW_HOVERED = 0x2000B;
    public static final int GLFW_FOCUS_ON_SHOW = 0x2000C;

    public static final int GLFW_RED_BITS = 0x21001;
    public static final int GLFW_GREEN_BITS = 0x21002;
    public static final int GLFW_BLUE_BITS = 0x21003;
    public static final int GLFW_ALPHA_BITS = 0x21004;
    public static final int GLFW_DEPTH_BITS = 0x21005;
    public static final int GLFW_STENCIL_BITS = 0x21006;
    public static final int GLFW_SAMPLES = 0x2100D;
    public static final int GLFW_DOUBLEBUFFER = 0x21010;
    public static final int GLFW_REFRESH_RATE = 0x2100F;

    public static final int GLFW_CLIENT_API = 0x22001;
    public static final int GLFW_CONTEXT_VERSION_MAJOR = 0x22002;
    public static final int GLFW_CONTEXT_VERSION_MINOR = 0x22003;
    public static final int GLFW_CONTEXT_REVISION = 0x22004;
    public static final int GLFW_CONTEXT_ROBUSTNESS = 0x22005;
    public static final int GLFW_OPENGL_FORWARD_COMPAT = 0x22006;
    public static final int GLFW_OPENGL_DEBUG_CONTEXT = 0x22007;
    public static final int GLFW_OPENGL_PROFILE = 0x22008;
    public static final int GLFW_CONTEXT_CREATION_API = 0x2200B;

    public static final int GLFW_NO_API = 0;
    public static final int GLFW_OPENGL_API = 0x30001;
    public static final int GLFW_OPENGL_ES_API = 0x30002;
    public static final int GLFW_OPENGL_ANY_PROFILE = 0;
    public static final int GLFW_OPENGL_CORE_PROFILE = 0x32001;
    public static final int GLFW_OPENGL_COMPAT_PROFILE = 0x32002;

    public static final int GLFW_CURSOR = 0x33001;
    public static final int GLFW_STICKY_KEYS = 0x33002;
    public static final int GLFW_STICKY_MOUSE_BUTTONS = 0x33003;
    public static final int GLFW_LOCK_KEY_MODS = 0x33004;
    public static final int GLFW_RAW_MOUSE_MOTION = 0x33005;

    public static final int GLFW_CURSOR_NORMAL = 0x34001;
    public static final int GLFW_CURSOR_HIDDEN = 0x34002;
    public static final int GLFW_CURSOR_DISABLED = 0x34003;

    public static final int GLFW_ARROW_CURSOR = 0x36001;
    public static final int GLFW_IBEAM_CURSOR = 0x36002;
    public static final int GLFW_CROSSHAIR_CURSOR = 0x36003;
    public static final int GLFW_HAND_CURSOR = 0x36004;
    public static final int GLFW_HRESIZE_CURSOR = 0x36005;
    public static final int GLFW_VRESIZE_CURSOR = 0x36006;

    public static final int GLFW_CONNECTED = 0x40001;
    public static final int GLFW_DISCONNECTED = 0x40002;

    public static final int GLFW_PLATFORM = 0x50003;
    public static final int GLFW_ANY_PLATFORM = 0x60000;
    public static final int GLFW_PLATFORM_WIN32 = 0x60001;
    public static final int GLFW_PLATFORM_COCOA = 0x60002;
    public static final int GLFW_PLATFORM_WAYLAND = 0x60003;
    public static final int GLFW_PLATFORM_X11 = 0x60004;
    public static final int GLFW_PLATFORM_NULL = 0x60005;
    public static final int GLFW_PLATFORM_UNAVAILABLE = 0x1000C;

    public static final int GLFW_MOUSE_BUTTON_1 = 0;
    public static final int GLFW_MOUSE_BUTTON_2 = 1;
    public static final int GLFW_MOUSE_BUTTON_3 = 2;
    public static final int GLFW_MOUSE_BUTTON_4 = 3;
    public static final int GLFW_MOUSE_BUTTON_5 = 4;
    public static final int GLFW_MOUSE_BUTTON_6 = 5;
    public static final int GLFW_MOUSE_BUTTON_7 = 6;
    public static final int GLFW_MOUSE_BUTTON_8 = 7;
    public static final int GLFW_MOUSE_BUTTON_LAST = GLFW_MOUSE_BUTTON_8;
    public static final int GLFW_MOUSE_BUTTON_LEFT = GLFW_MOUSE_BUTTON_1;
    public static final int GLFW_MOUSE_BUTTON_RIGHT = GLFW_MOUSE_BUTTON_2;
    public static final int GLFW_MOUSE_BUTTON_MIDDLE = GLFW_MOUSE_BUTTON_3;

    public static final int GLFW_MOD_SHIFT = 0x0001;
    public static final int GLFW_MOD_CONTROL = 0x0002;
    public static final int GLFW_MOD_ALT = 0x0004;
    public static final int GLFW_MOD_SUPER = 0x0008;
    public static final int GLFW_MOD_CAPS_LOCK = 0x0010;
    public static final int GLFW_MOD_NUM_LOCK = 0x0020;

    public static final int GLFW_KEY_UNKNOWN = -1;
    public static final int GLFW_KEY_SPACE = 32;
    public static final int GLFW_KEY_APOSTROPHE = 39;
    public static final int GLFW_KEY_COMMA = 44;
    public static final int GLFW_KEY_MINUS = 45;
    public static final int GLFW_KEY_PERIOD = 46;
    public static final int GLFW_KEY_SLASH = 47;
    public static final int GLFW_KEY_0 = 48;
    public static final int GLFW_KEY_1 = 49;
    public static final int GLFW_KEY_2 = 50;
    public static final int GLFW_KEY_3 = 51;
    public static final int GLFW_KEY_4 = 52;
    public static final int GLFW_KEY_5 = 53;
    public static final int GLFW_KEY_6 = 54;
    public static final int GLFW_KEY_7 = 55;
    public static final int GLFW_KEY_8 = 56;
    public static final int GLFW_KEY_9 = 57;
    public static final int GLFW_KEY_SEMICOLON = 59;
    public static final int GLFW_KEY_EQUAL = 61;
    public static final int GLFW_KEY_A = 65;
    public static final int GLFW_KEY_B = 66;
    public static final int GLFW_KEY_C = 67;
    public static final int GLFW_KEY_D = 68;
    public static final int GLFW_KEY_E = 69;
    public static final int GLFW_KEY_F = 70;
    public static final int GLFW_KEY_G = 71;
    public static final int GLFW_KEY_H = 72;
    public static final int GLFW_KEY_I = 73;
    public static final int GLFW_KEY_J = 74;
    public static final int GLFW_KEY_K = 75;
    public static final int GLFW_KEY_L = 76;
    public static final int GLFW_KEY_M = 77;
    public static final int GLFW_KEY_N = 78;
    public static final int GLFW_KEY_O = 79;
    public static final int GLFW_KEY_P = 80;
    public static final int GLFW_KEY_Q = 81;
    public static final int GLFW_KEY_R = 82;
    public static final int GLFW_KEY_S = 83;
    public static final int GLFW_KEY_T = 84;
    public static final int GLFW_KEY_U = 85;
    public static final int GLFW_KEY_V = 86;
    public static final int GLFW_KEY_W = 87;
    public static final int GLFW_KEY_X = 88;
    public static final int GLFW_KEY_Y = 89;
    public static final int GLFW_KEY_Z = 90;
    public static final int GLFW_KEY_LEFT_BRACKET = 91;
    public static final int GLFW_KEY_BACKSLASH = 92;
    public static final int GLFW_KEY_RIGHT_BRACKET = 93;
    public static final int GLFW_KEY_GRAVE_ACCENT = 96;
    public static final int GLFW_KEY_WORLD_1 = 161;
    public static final int GLFW_KEY_WORLD_2 = 162;
    public static final int GLFW_KEY_ESCAPE = 256;
    public static final int GLFW_KEY_ENTER = 257;
    public static final int GLFW_KEY_TAB = 258;
    public static final int GLFW_KEY_BACKSPACE = 259;
    public static final int GLFW_KEY_INSERT = 260;
    public static final int GLFW_KEY_DELETE = 261;
    public static final int GLFW_KEY_RIGHT = 262;
    public static final int GLFW_KEY_LEFT = 263;
    public static final int GLFW_KEY_DOWN = 264;
    public static final int GLFW_KEY_UP = 265;
    public static final int GLFW_KEY_PAGE_UP = 266;
    public static final int GLFW_KEY_PAGE_DOWN = 267;
    public static final int GLFW_KEY_HOME = 268;
    public static final int GLFW_KEY_END = 269;
    public static final int GLFW_KEY_CAPS_LOCK = 280;
    public static final int GLFW_KEY_SCROLL_LOCK = 281;
    public static final int GLFW_KEY_NUM_LOCK = 282;
    public static final int GLFW_KEY_PRINT_SCREEN = 283;
    public static final int GLFW_KEY_PAUSE = 284;
    public static final int GLFW_KEY_F1 = 290;
    public static final int GLFW_KEY_F2 = 291;
    public static final int GLFW_KEY_F3 = 292;
    public static final int GLFW_KEY_F4 = 293;
    public static final int GLFW_KEY_F5 = 294;
    public static final int GLFW_KEY_F6 = 295;
    public static final int GLFW_KEY_F7 = 296;
    public static final int GLFW_KEY_F8 = 297;
    public static final int GLFW_KEY_F9 = 298;
    public static final int GLFW_KEY_F10 = 299;
    public static final int GLFW_KEY_F11 = 300;
    public static final int GLFW_KEY_F12 = 301;
    public static final int GLFW_KEY_F13 = 302;
    public static final int GLFW_KEY_F14 = 303;
    public static final int GLFW_KEY_F15 = 304;
    public static final int GLFW_KEY_F16 = 305;
    public static final int GLFW_KEY_F17 = 306;
    public static final int GLFW_KEY_F18 = 307;
    public static final int GLFW_KEY_F19 = 308;
    public static final int GLFW_KEY_F20 = 309;
    public static final int GLFW_KEY_F21 = 310;
    public static final int GLFW_KEY_F22 = 311;
    public static final int GLFW_KEY_F23 = 312;
    public static final int GLFW_KEY_F24 = 313;
    public static final int GLFW_KEY_F25 = 314;
    public static final int GLFW_KEY_KP_0 = 320;
    public static final int GLFW_KEY_KP_1 = 321;
    public static final int GLFW_KEY_KP_2 = 322;
    public static final int GLFW_KEY_KP_3 = 323;
    public static final int GLFW_KEY_KP_4 = 324;
    public static final int GLFW_KEY_KP_5 = 325;
    public static final int GLFW_KEY_KP_6 = 326;
    public static final int GLFW_KEY_KP_7 = 327;
    public static final int GLFW_KEY_KP_8 = 328;
    public static final int GLFW_KEY_KP_9 = 329;
    public static final int GLFW_KEY_KP_DECIMAL = 330;
    public static final int GLFW_KEY_KP_DIVIDE = 331;
    public static final int GLFW_KEY_KP_MULTIPLY = 332;
    public static final int GLFW_KEY_KP_SUBTRACT = 333;
    public static final int GLFW_KEY_KP_ADD = 334;
    public static final int GLFW_KEY_KP_ENTER = 335;
    public static final int GLFW_KEY_KP_EQUAL = 336;
    public static final int GLFW_KEY_LEFT_SHIFT = 340;
    public static final int GLFW_KEY_LEFT_CONTROL = 341;
    public static final int GLFW_KEY_LEFT_ALT = 342;
    public static final int GLFW_KEY_LEFT_SUPER = 343;
    public static final int GLFW_KEY_RIGHT_SHIFT = 344;
    public static final int GLFW_KEY_RIGHT_CONTROL = 345;
    public static final int GLFW_KEY_RIGHT_ALT = 346;
    public static final int GLFW_KEY_RIGHT_SUPER = 347;
    public static final int GLFW_KEY_MENU = 348;
    public static final int GLFW_KEY_LAST = GLFW_KEY_MENU;

    public static final int GLFW_JOYSTICK_1 = 0;
    public static final int GLFW_JOYSTICK_LAST = 15;

    private static final long WINDOW_HANDLE = 0x4D4C4E44L;
    private static final long MONITOR_HANDLE = 0x4D4F4E31L;

    private static final int MAX_KEYS = GLFW_KEY_LAST + 1;
    private static final boolean[] KEY_STATE = new boolean[MAX_KEYS];
    private static final boolean[] MOUSE_STATE = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private static final int[] EVENT = new int[5];

    private static boolean initialised;
    private static boolean focused = true;
    private static boolean glReady;
    private static PointerBuffer monitorList;
    private static boolean shouldClose;
    private static boolean contextCurrent;
    private static int cursorMode = GLFW_CURSOR_NORMAL;
    private static int modifiers;
    private static double cursorX;
    private static double cursorY;
    private static int width = 854;
    private static int height = 480;
    private static long startedAt = System.nanoTime();
    private static int lastError;

    private static GLFWErrorCallback errorCallback;
    private static GLFWKeyCallback keyCallback;
    private static GLFWCharCallback charCallback;
    private static GLFWCharModsCallback charModsCallback;
    private static GLFWCursorPosCallback cursorPosCallback;
    private static GLFWCursorEnterCallback cursorEnterCallback;
    private static GLFWMouseButtonCallback mouseButtonCallback;
    private static GLFWScrollCallback scrollCallback;
    private static GLFWWindowSizeCallback windowSizeCallback;
    private static GLFWWindowPosCallback windowPosCallback;
    private static GLFWWindowFocusCallback windowFocusCallback;
    private static GLFWWindowCloseCallback windowCloseCallback;
    private static GLFWWindowIconifyCallback windowIconifyCallback;
    private static GLFWWindowMaximizeCallback windowMaximizeCallback;
    private static GLFWWindowRefreshCallback windowRefreshCallback;
    private static GLFWWindowContentScaleCallback windowContentScaleCallback;
    private static GLFWFramebufferSizeCallback framebufferSizeCallback;
    private static GLFWDropCallback dropCallback;
    private static GLFWJoystickCallback joystickCallback;
    private static GLFWMonitorCallback monitorCallback;

    private GLFW() {
    }

    static void setErrorCallbackInternal(GLFWErrorCallback callback) {
        errorCallback = callback;
    }

    public static boolean glfwInit() {
        initialised = true;
        shouldClose = false;
        startedAt = System.nanoTime();
        Arrays.fill(KEY_STATE, false);
        Arrays.fill(MOUSE_STATE, false);
        return true;
    }

    public static void glfwTerminate() {
        initialised = false;
        contextCurrent = false;
        modifiers = 0;
        cursorMode = GLFW_CURSOR_NORMAL;
        clipboard = "";
        Arrays.fill(KEY_STATE, false);
        Arrays.fill(MOUSE_STATE, false);
    }

    static void registerCallback(long window, Object callback) {
        if (callback instanceof GLFWKeyCallbackI) {
            glfwSetKeyCallback(window, (GLFWKeyCallbackI) callback);
        } else if (callback instanceof GLFWCharCallbackI) {
            glfwSetCharCallback(window, (GLFWCharCallbackI) callback);
        } else if (callback instanceof GLFWCharModsCallbackI) {
            glfwSetCharModsCallback(window, (GLFWCharModsCallbackI) callback);
        } else if (callback instanceof GLFWCursorPosCallbackI) {
            glfwSetCursorPosCallback(window, (GLFWCursorPosCallbackI) callback);
        } else if (callback instanceof GLFWCursorEnterCallbackI) {
            glfwSetCursorEnterCallback(window, (GLFWCursorEnterCallbackI) callback);
        } else if (callback instanceof GLFWMouseButtonCallbackI) {
            glfwSetMouseButtonCallback(window, (GLFWMouseButtonCallbackI) callback);
        } else if (callback instanceof GLFWScrollCallbackI) {
            glfwSetScrollCallback(window, (GLFWScrollCallbackI) callback);
        } else if (callback instanceof GLFWWindowSizeCallbackI) {
            glfwSetWindowSizeCallback(window, (GLFWWindowSizeCallbackI) callback);
        } else if (callback instanceof GLFWFramebufferSizeCallbackI) {
            glfwSetFramebufferSizeCallback(window, (GLFWFramebufferSizeCallbackI) callback);
        } else if (callback instanceof GLFWWindowFocusCallbackI) {
            glfwSetWindowFocusCallback(window, (GLFWWindowFocusCallbackI) callback);
        } else if (callback instanceof GLFWWindowCloseCallbackI) {
            glfwSetWindowCloseCallback(window, (GLFWWindowCloseCallbackI) callback);
        } else if (callback instanceof GLFWWindowPosCallbackI) {
            glfwSetWindowPosCallback(window, (GLFWWindowPosCallbackI) callback);
        } else if (callback instanceof GLFWWindowIconifyCallbackI) {
            glfwSetWindowIconifyCallback(window, (GLFWWindowIconifyCallbackI) callback);
        } else if (callback instanceof GLFWWindowMaximizeCallbackI) {
            glfwSetWindowMaximizeCallback(window, (GLFWWindowMaximizeCallbackI) callback);
        } else if (callback instanceof GLFWWindowRefreshCallbackI) {
            glfwSetWindowRefreshCallback(window, (GLFWWindowRefreshCallbackI) callback);
        } else if (callback instanceof GLFWWindowContentScaleCallbackI) {
            glfwSetWindowContentScaleCallback(window, (GLFWWindowContentScaleCallbackI) callback);
        } else if (callback instanceof GLFWDropCallbackI) {
            glfwSetDropCallback(window, (GLFWDropCallbackI) callback);
        } else if (callback instanceof GLFWErrorCallbackI) {
            glfwSetErrorCallback((GLFWErrorCallbackI) callback);
        } else if (callback instanceof GLFWJoystickCallbackI) {
            glfwSetJoystickCallback((GLFWJoystickCallbackI) callback);
        } else if (callback instanceof GLFWMonitorCallbackI) {
            glfwSetMonitorCallback((GLFWMonitorCallbackI) callback);
        }
    }

    public static String glfwGetVersionString() {
        return "3.3.8 Millida bridge";
    }

    public static void glfwGetVersion(IntBuffer major, IntBuffer minor, IntBuffer rev) {
        if (major != null) major.put(major.position(), 3);
        if (minor != null) minor.put(minor.position(), 3);
        if (rev != null) rev.put(rev.position(), 8);
    }

    public static int glfwGetError(PointerBuffer description) {
        // The game reads the description as a C string, so a stale pointer would
        // crash it; NULL is the documented "no text" answer.
        if (description != null && description.remaining() > 0) {
            description.put(description.position(), 0L);
        }
        final int error = lastError;
        lastError = 0;
        return error;
    }

    /**
     * The game asks which windowing system it runs on and takes a different path
     * for Wayland. X11 is the answer that keeps it on the plain desktop path,
     * which is the one this bridge imitates.
     */
    public static int glfwGetPlatform() {
        return GLFW_PLATFORM_X11;
    }

    public static boolean glfwPlatformSupported(int platform) {
        return platform == GLFW_PLATFORM_X11 || platform == GLFW_ANY_PLATFORM;
    }

    public static void glfwInitHint(int hint, int value) {
    }

    public static void glfwDefaultWindowHints() {
    }

    public static void glfwWindowHint(int hint, int value) {
    }

    public static void glfwWindowHintString(int hint, CharSequence value) {
    }

    public static long glfwCreateWindow(int requestedWidth, int requestedHeight, CharSequence title, long monitor, long share) {
        if (!initialised) {
            lastError = GLFW_NOT_INITIALIZED;
            return 0L;
        }
        pumpWindowSize();
        if (requestedWidth > 0 && width <= 0) width = requestedWidth;
        if (requestedHeight > 0 && height <= 0) height = requestedHeight;
        return WINDOW_HANDLE;
    }

    public static void glfwDestroyWindow(long window) {
        contextCurrent = false;
    }

    public static boolean glfwWindowShouldClose(long window) {
        return shouldClose;
    }

    public static void glfwSetWindowShouldClose(long window, boolean value) {
        shouldClose = value;
    }

    public static void glfwSetWindowTitle(long window, CharSequence title) {
    }

    public static void glfwSetWindowIcon(long window, GLFWImage.Buffer images) {
    }

    public static void glfwShowWindow(long window) {
    }

    public static void glfwHideWindow(long window) {
    }

    public static void glfwFocusWindow(long window) {
        if (windowFocusCallback != null) windowFocusCallback.invoke(window, true);
    }

    public static void glfwIconifyWindow(long window) {
    }

    public static void glfwRestoreWindow(long window) {
    }

    public static void glfwMaximizeWindow(long window) {
    }

    public static void glfwSetWindowPos(long window, int x, int y) {
    }

    public static void glfwGetWindowPos(long window, int[] x, int[] y) {
        if (x != null && x.length > 0) x[0] = 0;
        if (y != null && y.length > 0) y[0] = 0;
    }

    public static void glfwGetWindowPos(long window, IntBuffer x, IntBuffer y) {
        if (x != null) x.put(x.position(), 0);
        if (y != null) y.put(y.position(), 0);
    }

    public static void glfwGetWindowSize(long window, int[] outWidth, int[] outHeight) {
        pumpWindowSize();
        if (outWidth != null && outWidth.length > 0) outWidth[0] = width;
        if (outHeight != null && outHeight.length > 0) outHeight[0] = height;
    }

    public static void glfwGetWindowSize(long window, IntBuffer outWidth, IntBuffer outHeight) {
        pumpWindowSize();
        if (outWidth != null) outWidth.put(outWidth.position(), width);
        if (outHeight != null) outHeight.put(outHeight.position(), height);
    }

    public static void glfwSetWindowSize(long window, int newWidth, int newHeight) {
    }

    public static void glfwSetWindowSizeLimits(long window, int minWidth, int minHeight, int maxWidth, int maxHeight) {
    }

    public static void glfwGetFramebufferSize(long window, int[] outWidth, int[] outHeight) {
        glfwGetWindowSize(window, outWidth, outHeight);
    }

    public static void glfwGetFramebufferSize(long window, IntBuffer outWidth, IntBuffer outHeight) {
        glfwGetWindowSize(window, outWidth, outHeight);
    }

    public static void glfwGetWindowContentScale(long window, FloatBuffer x, FloatBuffer y) {
        if (x != null) x.put(x.position(), 1.0f);
        if (y != null) y.put(y.position(), 1.0f);
    }

    public static int glfwGetWindowAttrib(long window, int attribute) {
        switch (attribute) {
            case GLFW_FOCUSED:
                return focused ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_VISIBLE:
            case GLFW_HOVERED:
                return GLFW_TRUE;
            case GLFW_ICONIFIED:
            case GLFW_MAXIMIZED:
            case GLFW_RESIZABLE:
            case GLFW_DECORATED:
                return GLFW_FALSE;
            default:
                return GLFW_FALSE;
        }
    }

    public static void glfwSetWindowAttrib(long window, int attribute, int value) {
    }

    public static long glfwGetWindowMonitor(long window) {
        return MONITOR_HANDLE;
    }

    public static void glfwSetWindowMonitor(long window, long monitor, int x, int y, int w, int h, int refreshRate) {
    }

    public static void glfwMakeContextCurrent(long window) {
        if (contextCurrent) return;
        final boolean ok = GlfwBridge.setupWindow(
            GlfwBridge.rendererDir(),
            GlfwBridge.rendererLibrary(),
            GlfwBridge.eglLibrary());
        contextCurrent = ok;
        if (!ok) {
            lastError = GLFW_PLATFORM_ERROR;
            if (errorCallback != null) errorCallback.invoke(GLFW_PLATFORM_ERROR, 0L);
            return;
        }
        bindOpenGl();
    }

    /**
     * The desktop loader would look for glXGetProcAddress inside the renderer,
     * which has no such symbol; the addresses come from the bridge instead.
     */
    private static void bindOpenGl() {
        if (glReady) return;
        try {
            org.lwjgl.opengl.GL.create(new FunctionProvider() {
                @Override
                public long getFunctionAddress(java.nio.ByteBuffer name) {
                    return GlfwBridge.procAddress(org.lwjgl.system.MemoryUtil.memASCII(name));
                }
            });
            glReady = true;
        } catch (Throwable failure) {
            lastError = GLFW_API_UNAVAILABLE;
            System.err.println("[Millida] OpenGL bridge failed: " + failure);
        }
    }

    public static long glfwGetProcAddress(CharSequence name) {
        return GlfwBridge.procAddress(name.toString());
    }

    /**
     * The game reads the library behind the window layer for its crash report
     * and for its own version check, so a real object has to come back.
     */
    public static org.lwjgl.system.SharedLibrary getLibrary() {
        return net.millida.launcher.bridge.MillidaLibrary.INSTANCE;
    }

    public static long glfwGetCurrentContext() {
        return contextCurrent ? WINDOW_HANDLE : 0L;
    }

    public static void glfwSwapBuffers(long window) {
        if (!contextCurrent) {
            glfwMakeContextCurrent(window);
            if (!contextCurrent) return;
        }
        if (!GlfwBridge.swapBuffers()) {
            // The surface is gone (window replaced, app in the background); the
            // context is taken again on the next frame instead of drawing blind.
            contextCurrent = false;
        }
    }

    public static void glfwSwapInterval(int interval) {
        GlfwBridge.setSwapInterval(interval);
    }

    public static boolean glfwExtensionSupported(CharSequence extension) {
        return false;
    }

    public static void glfwPollEvents() {
        pumpWindowSize();
        while (GlfwBridge.pollEvent(EVENT)) {
            dispatch(EVENT[0], EVENT[1], EVENT[2], EVENT[3], EVENT[4]);
        }
    }

    public static void glfwWaitEvents() {
        glfwPollEvents();
    }

    public static void glfwWaitEventsTimeout(double timeout) {
        glfwPollEvents();
    }

    public static void glfwPostEmptyEvent() {
    }

    public static int glfwGetKey(long window, int key) {
        if (key < 0 || key >= MAX_KEYS) return GLFW_RELEASE;
        return KEY_STATE[key] ? GLFW_PRESS : GLFW_RELEASE;
    }

    public static int glfwGetMouseButton(long window, int button) {
        if (button < 0 || button >= MOUSE_STATE.length) return GLFW_RELEASE;
        return MOUSE_STATE[button] ? GLFW_PRESS : GLFW_RELEASE;
    }

    public static void glfwGetCursorPos(long window, double[] x, double[] y) {
        if (x != null && x.length > 0) x[0] = cursorX;
        if (y != null && y.length > 0) y[0] = cursorY;
    }

    public static void glfwGetCursorPos(long window, DoubleBuffer x, DoubleBuffer y) {
        if (x != null) x.put(x.position(), cursorX);
        if (y != null) y.put(y.position(), cursorY);
    }

    public static void glfwSetCursorPos(long window, double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    public static void glfwSetInputMode(long window, int mode, int value) {
        if (mode == GLFW_CURSOR) cursorMode = value;
    }

    public static int glfwGetInputMode(long window, int mode) {
        return mode == GLFW_CURSOR ? cursorMode : GLFW_FALSE;
    }

    public static boolean glfwRawMouseMotionSupported() {
        return false;
    }

    public static String glfwGetKeyName(int key, int scancode) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) {
            return String.valueOf((char) ('a' + (key - GLFW_KEY_A)));
        }
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9) {
            return String.valueOf((char) ('0' + (key - GLFW_KEY_0)));
        }
        return null;
    }

    public static int glfwGetKeyScancode(int key) {
        return key;
    }

    public static long glfwCreateStandardCursor(int shape) {
        return 0L;
    }

    public static void glfwSetCursor(long window, long cursor) {
    }

    public static void glfwDestroyCursor(long cursor) {
    }

    public static String glfwGetClipboardString(long window) {
        return clipboard;
    }

    public static void glfwSetClipboardString(long window, CharSequence text) {
        clipboard = text == null ? "" : text.toString();
    }

    public static double glfwGetTime() {
        return (System.nanoTime() - startedAt) / 1_000_000_000.0;
    }

    public static void glfwSetTime(double time) {
        startedAt = System.nanoTime() - (long) (time * 1_000_000_000.0);
    }

    public static long glfwGetTimerValue() {
        return System.nanoTime();
    }

    public static long glfwGetTimerFrequency() {
        return 1_000_000_000L;
    }

    public static long glfwGetPrimaryMonitor() {
        return MONITOR_HANDLE;
    }

    public static PointerBuffer glfwGetMonitors() {
        if (monitorList == null) {
            monitorList = PointerBuffer.allocateDirect(1);
            monitorList.put(0, MONITOR_HANDLE);
        }
        monitorList.position(0);
        monitorList.limit(1);
        return monitorList;
    }

    public static GLFWVidMode glfwGetVideoMode(long monitor) {
        pumpWindowSize();
        return new GLFWVidMode(width, height, 60);
    }

    public static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) {
        return new GLFWVidMode.Buffer(new GLFWVidMode[]{glfwGetVideoMode(monitor)});
    }

    public static void glfwGetMonitorPos(long monitor, int[] x, int[] y) {
        if (x != null && x.length > 0) x[0] = 0;
        if (y != null && y.length > 0) y[0] = 0;
    }

    public static void glfwGetMonitorPos(long monitor, IntBuffer x, IntBuffer y) {
        if (x != null) x.put(x.position(), 0);
        if (y != null) y.put(y.position(), 0);
    }

    public static void glfwGetMonitorPhysicalSize(long monitor, IntBuffer widthMm, IntBuffer heightMm) {
        if (widthMm != null) widthMm.put(widthMm.position(), 150);
        if (heightMm != null) heightMm.put(heightMm.position(), 70);
    }

    public static void glfwGetMonitorContentScale(long monitor, FloatBuffer x, FloatBuffer y) {
        if (x != null) x.put(x.position(), 1.0f);
        if (y != null) y.put(y.position(), 1.0f);
    }

    public static String glfwGetMonitorName(long monitor) {
        return "Экран устройства";
    }

    public static boolean glfwJoystickPresent(int jid) {
        return false;
    }

    public static FloatBuffer glfwGetJoystickAxes(int jid) {
        return null;
    }

    public static java.nio.ByteBuffer glfwGetJoystickButtons(int jid) {
        return null;
    }

    public static String glfwGetJoystickName(int jid) {
        return null;
    }

    public static String glfwGetJoystickGUID(int jid) {
        return null;
    }

    public static boolean glfwJoystickIsGamepad(int jid) {
        return false;
    }

    public static String glfwGetGamepadName(int jid) {
        return null;
    }

    public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI callback) {
        final GLFWKeyCallback previous = keyCallback;
        keyCallback = callback == null ? null : GLFWKeyCallback.create(callback);
        return previous;
    }

    public static GLFWCharCallback glfwSetCharCallback(long window, GLFWCharCallbackI callback) {
        final GLFWCharCallback previous = charCallback;
        charCallback = callback == null ? null : GLFWCharCallback.create(callback);
        return previous;
    }

    public static GLFWCharModsCallback glfwSetCharModsCallback(long window, GLFWCharModsCallbackI callback) {
        final GLFWCharModsCallback previous = charModsCallback;
        charModsCallback = callback == null ? null : GLFWCharModsCallback.create(callback);
        return previous;
    }

    public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI callback) {
        final GLFWCursorPosCallback previous = cursorPosCallback;
        cursorPosCallback = callback == null ? null : GLFWCursorPosCallback.create(callback);
        return previous;
    }

    public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI callback) {
        final GLFWCursorEnterCallback previous = cursorEnterCallback;
        cursorEnterCallback = callback == null ? null : GLFWCursorEnterCallback.create(callback);
        return previous;
    }

    public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI callback) {
        final GLFWMouseButtonCallback previous = mouseButtonCallback;
        mouseButtonCallback = callback == null ? null : GLFWMouseButtonCallback.create(callback);
        return previous;
    }

    public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI callback) {
        final GLFWScrollCallback previous = scrollCallback;
        scrollCallback = callback == null ? null : GLFWScrollCallback.create(callback);
        return previous;
    }

    public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI callback) {
        final GLFWWindowSizeCallback previous = windowSizeCallback;
        windowSizeCallback = callback == null ? null : GLFWWindowSizeCallback.create(callback);
        return previous;
    }

    public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI callback) {
        final GLFWWindowPosCallback previous = windowPosCallback;
        windowPosCallback = callback == null ? null : GLFWWindowPosCallback.create(callback);
        return previous;
    }

    public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI callback) {
        final GLFWWindowFocusCallback previous = windowFocusCallback;
        windowFocusCallback = callback == null ? null : GLFWWindowFocusCallback.create(callback);
        return previous;
    }

    public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI callback) {
        final GLFWWindowCloseCallback previous = windowCloseCallback;
        windowCloseCallback = callback == null ? null : GLFWWindowCloseCallback.create(callback);
        return previous;
    }

    public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI callback) {
        final GLFWWindowIconifyCallback previous = windowIconifyCallback;
        windowIconifyCallback = callback == null ? null : GLFWWindowIconifyCallback.create(callback);
        return previous;
    }

    public static GLFWWindowMaximizeCallback glfwSetWindowMaximizeCallback(long window, GLFWWindowMaximizeCallbackI callback) {
        final GLFWWindowMaximizeCallback previous = windowMaximizeCallback;
        windowMaximizeCallback = callback == null ? null : GLFWWindowMaximizeCallback.create(callback);
        return previous;
    }

    public static GLFWWindowRefreshCallback glfwSetWindowRefreshCallback(long window, GLFWWindowRefreshCallbackI callback) {
        final GLFWWindowRefreshCallback previous = windowRefreshCallback;
        windowRefreshCallback = callback == null ? null : GLFWWindowRefreshCallback.create(callback);
        return previous;
    }

    public static GLFWWindowContentScaleCallback glfwSetWindowContentScaleCallback(long window, GLFWWindowContentScaleCallbackI callback) {
        final GLFWWindowContentScaleCallback previous = windowContentScaleCallback;
        windowContentScaleCallback = callback == null ? null : GLFWWindowContentScaleCallback.create(callback);
        return previous;
    }

    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI callback) {
        final GLFWFramebufferSizeCallback previous = framebufferSizeCallback;
        framebufferSizeCallback = callback == null ? null : GLFWFramebufferSizeCallback.create(callback);
        return previous;
    }

    public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI callback) {
        final GLFWDropCallback previous = dropCallback;
        dropCallback = callback == null ? null : GLFWDropCallback.create(callback);
        return previous;
    }

    public static GLFWJoystickCallback glfwSetJoystickCallback(GLFWJoystickCallbackI callback) {
        final GLFWJoystickCallback previous = joystickCallback;
        joystickCallback = callback == null ? null : GLFWJoystickCallback.create(callback);
        return previous;
    }

    public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI callback) {
        final GLFWMonitorCallback previous = monitorCallback;
        monitorCallback = callback == null ? null : GLFWMonitorCallback.create(callback);
        return previous;
    }

    public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI callback) {
        final GLFWErrorCallback previous = errorCallback;
        errorCallback = callback == null ? null : GLFWErrorCallback.create(callback);
        return previous;
    }

    private static String clipboard = "";

    private static void pumpWindowSize() {
        final int nativeWidth = GlfwBridge.windowWidth();
        final int nativeHeight = GlfwBridge.windowHeight();
        if (nativeWidth > 0 && nativeHeight > 0 && (nativeWidth != width || nativeHeight != height)) {
            width = nativeWidth;
            height = nativeHeight;
            if (windowSizeCallback != null) windowSizeCallback.invoke(WINDOW_HANDLE, width, height);
            if (framebufferSizeCallback != null) framebufferSizeCallback.invoke(WINDOW_HANDLE, width, height);
        }
    }

    private static void dispatch(int kind, int a, int b, int c, int d) {
        switch (kind) {
            case GlfwBridge.EVENT_CURSOR: {
                cursorX = a;
                cursorY = b;
                if (cursorPosCallback != null) cursorPosCallback.invoke(WINDOW_HANDLE, cursorX, cursorY);
                break;
            }
            case GlfwBridge.EVENT_MOUSE_BUTTON: {
                final boolean pressed = b != 0;
                if (a >= 0 && a < MOUSE_STATE.length) MOUSE_STATE[a] = pressed;
                if (mouseButtonCallback != null) {
                    mouseButtonCallback.invoke(WINDOW_HANDLE, a, pressed ? GLFW_PRESS : GLFW_RELEASE, modifiers);
                }
                break;
            }
            case GlfwBridge.EVENT_KEY: {
                final boolean pressed = b != 0;
                final int action = b == GLFW_REPEAT ? GLFW_REPEAT : pressed ? GLFW_PRESS : GLFW_RELEASE;
                if (a >= 0 && a < MAX_KEYS) KEY_STATE[a] = pressed;
                trackModifier(a, pressed);
                final int scancode = c != 0 ? c : a;
                if (keyCallback != null) {
                    keyCallback.invoke(WINDOW_HANDLE, a, scancode, action, modifiers);
                }
                break;
            }
            case GlfwBridge.EVENT_CURSOR_DELTA: {
                cursorX += a;
                cursorY += b;
                if (cursorPosCallback != null) cursorPosCallback.invoke(WINDOW_HANDLE, cursorX, cursorY);
                break;
            }
            case GlfwBridge.EVENT_CLOSE: {
                shouldClose = true;
                if (windowCloseCallback != null) windowCloseCallback.invoke(WINDOW_HANDLE);
                break;
            }
            case GlfwBridge.EVENT_CHAR: {
                if (charCallback != null) charCallback.invoke(WINDOW_HANDLE, a);
                if (charModsCallback != null) charModsCallback.invoke(WINDOW_HANDLE, a, modifiers);
                break;
            }
            case GlfwBridge.EVENT_SCROLL: {
                if (scrollCallback != null) scrollCallback.invoke(WINDOW_HANDLE, a, b);
                break;
            }
            case GlfwBridge.EVENT_WINDOW_SIZE: {
                width = a;
                height = b;
                if (windowSizeCallback != null) windowSizeCallback.invoke(WINDOW_HANDLE, width, height);
                if (framebufferSizeCallback != null) framebufferSizeCallback.invoke(WINDOW_HANDLE, width, height);
                break;
            }
            case GlfwBridge.EVENT_FOCUS: {
                focused = a != 0;
                if (windowFocusCallback != null) windowFocusCallback.invoke(WINDOW_HANDLE, focused);
                break;
            }
            default:
                break;
        }
    }

    private static void trackModifier(int key, boolean pressed) {
        final int bit;
        switch (key) {
            case GLFW_KEY_LEFT_SHIFT:
            case GLFW_KEY_RIGHT_SHIFT:
                bit = GLFW_MOD_SHIFT;
                break;
            case GLFW_KEY_LEFT_CONTROL:
            case GLFW_KEY_RIGHT_CONTROL:
                bit = GLFW_MOD_CONTROL;
                break;
            case GLFW_KEY_LEFT_ALT:
            case GLFW_KEY_RIGHT_ALT:
                bit = GLFW_MOD_ALT;
                break;
            case GLFW_KEY_LEFT_SUPER:
            case GLFW_KEY_RIGHT_SUPER:
                bit = GLFW_MOD_SUPER;
                break;
            default:
                return;
        }
        modifiers = pressed ? (modifiers | bit) : (modifiers & ~bit);
    }
}
