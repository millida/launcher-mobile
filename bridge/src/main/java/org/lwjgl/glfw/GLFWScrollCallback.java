package org.lwjgl.glfw;

public abstract class GLFWScrollCallback implements GLFWScrollCallbackI {

    protected GLFWScrollCallback() {
    }

    public static GLFWScrollCallback create(GLFWScrollCallbackI delegate) {
        if (delegate instanceof GLFWScrollCallback) {
            return (GLFWScrollCallback) delegate;
        }
        return new GLFWScrollCallback() {
            @Override
            public void invoke(long window, double xoffset, double yoffset) {
                delegate.invoke(window, xoffset, yoffset);
            }
        };
    }

    public static GLFWScrollCallback createSafe(long handle) {
        return null;
    }

    public GLFWScrollCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
