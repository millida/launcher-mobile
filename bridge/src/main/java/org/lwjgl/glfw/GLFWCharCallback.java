package org.lwjgl.glfw;

public abstract class GLFWCharCallback implements GLFWCharCallbackI {

    protected GLFWCharCallback() {
    }

    public static GLFWCharCallback create(GLFWCharCallbackI delegate) {
        if (delegate instanceof GLFWCharCallback) {
            return (GLFWCharCallback) delegate;
        }
        return new GLFWCharCallback() {
            @Override
            public void invoke(long window, int codepoint) {
                delegate.invoke(window, codepoint);
            }
        };
    }

    public static GLFWCharCallback createSafe(long handle) {
        return null;
    }

    public GLFWCharCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
