package org.lwjgl.glfw;

public abstract class GLFWWindowContentScaleCallback implements GLFWWindowContentScaleCallbackI {

    protected GLFWWindowContentScaleCallback() {
    }

    public static GLFWWindowContentScaleCallback create(GLFWWindowContentScaleCallbackI delegate) {
        if (delegate instanceof GLFWWindowContentScaleCallback) {
            return (GLFWWindowContentScaleCallback) delegate;
        }
        return new GLFWWindowContentScaleCallback() {
            @Override
            public void invoke(long window, float xscale, float yscale) {
                delegate.invoke(window, xscale, yscale);
            }
        };
    }

    public static GLFWWindowContentScaleCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowContentScaleCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
