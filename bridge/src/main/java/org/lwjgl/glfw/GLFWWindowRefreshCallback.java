package org.lwjgl.glfw;

public abstract class GLFWWindowRefreshCallback implements GLFWWindowRefreshCallbackI {

    protected GLFWWindowRefreshCallback() {
    }

    public static GLFWWindowRefreshCallback create(GLFWWindowRefreshCallbackI delegate) {
        if (delegate instanceof GLFWWindowRefreshCallback) {
            return (GLFWWindowRefreshCallback) delegate;
        }
        return new GLFWWindowRefreshCallback() {
            @Override
            public void invoke(long window) {
                delegate.invoke(window);
            }
        };
    }

    public static GLFWWindowRefreshCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowRefreshCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
