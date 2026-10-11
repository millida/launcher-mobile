package org.lwjgl.glfw;

public abstract class GLFWWindowFocusCallback implements GLFWWindowFocusCallbackI {

    protected GLFWWindowFocusCallback() {
    }

    public static GLFWWindowFocusCallback create(GLFWWindowFocusCallbackI delegate) {
        if (delegate instanceof GLFWWindowFocusCallback) {
            return (GLFWWindowFocusCallback) delegate;
        }
        return new GLFWWindowFocusCallback() {
            @Override
            public void invoke(long window, boolean focused) {
                delegate.invoke(window, focused);
            }
        };
    }

    public static GLFWWindowFocusCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowFocusCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
