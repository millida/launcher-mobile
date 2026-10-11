package org.lwjgl.glfw;

/**
 * The game clears every window callback through this helper before destroying
 * the window. The desktop version frees native closures; here the callbacks are
 * plain objects, so clearing the references is all there is to do.
 */
public final class Callbacks {

    private Callbacks() {
    }

    public static void glfwFreeCallbacks(long window) {
        GLFW.glfwSetKeyCallback(window, null);
        GLFW.glfwSetCharCallback(window, null);
        GLFW.glfwSetCharModsCallback(window, null);
        GLFW.glfwSetCursorPosCallback(window, null);
        GLFW.glfwSetCursorEnterCallback(window, null);
        GLFW.glfwSetMouseButtonCallback(window, null);
        GLFW.glfwSetScrollCallback(window, null);
        GLFW.glfwSetWindowSizeCallback(window, null);
        GLFW.glfwSetFramebufferSizeCallback(window, null);
        GLFW.glfwSetWindowFocusCallback(window, null);
        GLFW.glfwSetWindowCloseCallback(window, null);
        GLFW.glfwSetWindowPosCallback(window, null);
        GLFW.glfwSetWindowIconifyCallback(window, null);
        GLFW.glfwSetWindowMaximizeCallback(window, null);
        GLFW.glfwSetWindowRefreshCallback(window, null);
        GLFW.glfwSetWindowContentScaleCallback(window, null);
        GLFW.glfwSetDropCallback(window, null);
    }
}
