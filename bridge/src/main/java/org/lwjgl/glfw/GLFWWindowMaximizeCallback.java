package org.lwjgl.glfw;

public abstract class GLFWWindowMaximizeCallback implements GLFWWindowMaximizeCallbackI {

    protected GLFWWindowMaximizeCallback() {
    }

    public static GLFWWindowMaximizeCallback create(GLFWWindowMaximizeCallbackI delegate) {
        if (delegate instanceof GLFWWindowMaximizeCallback) {
            return (GLFWWindowMaximizeCallback) delegate;
        }
        return new GLFWWindowMaximizeCallback() {
            @Override
            public void invoke(long window, boolean maximized) {
                delegate.invoke(window, maximized);
            }
        };
    }

    public static GLFWWindowMaximizeCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowMaximizeCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
