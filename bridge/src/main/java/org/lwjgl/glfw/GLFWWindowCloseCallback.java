package org.lwjgl.glfw;

public abstract class GLFWWindowCloseCallback implements GLFWWindowCloseCallbackI {

    protected GLFWWindowCloseCallback() {
    }

    public static GLFWWindowCloseCallback create(GLFWWindowCloseCallbackI delegate) {
        if (delegate instanceof GLFWWindowCloseCallback) {
            return (GLFWWindowCloseCallback) delegate;
        }
        return new GLFWWindowCloseCallback() {
            @Override
            public void invoke(long window) {
                delegate.invoke(window);
            }
        };
    }

    public static GLFWWindowCloseCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowCloseCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
