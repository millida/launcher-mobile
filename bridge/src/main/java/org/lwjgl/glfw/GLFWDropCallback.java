package org.lwjgl.glfw;

public abstract class GLFWDropCallback implements GLFWDropCallbackI {

    protected GLFWDropCallback() {
    }

    public static GLFWDropCallback create(GLFWDropCallbackI delegate) {
        if (delegate instanceof GLFWDropCallback) {
            return (GLFWDropCallback) delegate;
        }
        return new GLFWDropCallback() {
            @Override
            public void invoke(long window, int count, long names) {
                delegate.invoke(window, count, names);
            }
        };
    }

    public static GLFWDropCallback createSafe(long handle) {
        return null;
    }

    public GLFWDropCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
