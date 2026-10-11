package org.lwjgl.glfw;

public abstract class GLFWCursorEnterCallback implements GLFWCursorEnterCallbackI {

    protected GLFWCursorEnterCallback() {
    }

    public static GLFWCursorEnterCallback create(GLFWCursorEnterCallbackI delegate) {
        if (delegate instanceof GLFWCursorEnterCallback) {
            return (GLFWCursorEnterCallback) delegate;
        }
        return new GLFWCursorEnterCallback() {
            @Override
            public void invoke(long window, boolean entered) {
                delegate.invoke(window, entered);
            }
        };
    }

    public static GLFWCursorEnterCallback createSafe(long handle) {
        return null;
    }

    public GLFWCursorEnterCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
