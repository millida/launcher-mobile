package org.lwjgl.glfw;

public abstract class GLFWWindowSizeCallback implements GLFWWindowSizeCallbackI {

    protected GLFWWindowSizeCallback() {
    }

    public static GLFWWindowSizeCallback create(GLFWWindowSizeCallbackI delegate) {
        if (delegate instanceof GLFWWindowSizeCallback) {
            return (GLFWWindowSizeCallback) delegate;
        }
        return new GLFWWindowSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                delegate.invoke(window, width, height);
            }
        };
    }

    public static GLFWWindowSizeCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowSizeCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
