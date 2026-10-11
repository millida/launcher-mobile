package org.lwjgl.glfw;

public abstract class GLFWMouseButtonCallback implements GLFWMouseButtonCallbackI {

    protected GLFWMouseButtonCallback() {
    }

    public static GLFWMouseButtonCallback create(GLFWMouseButtonCallbackI delegate) {
        if (delegate instanceof GLFWMouseButtonCallback) {
            return (GLFWMouseButtonCallback) delegate;
        }
        return new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                delegate.invoke(window, button, action, mods);
            }
        };
    }

    public static GLFWMouseButtonCallback createSafe(long handle) {
        return null;
    }

    public GLFWMouseButtonCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
