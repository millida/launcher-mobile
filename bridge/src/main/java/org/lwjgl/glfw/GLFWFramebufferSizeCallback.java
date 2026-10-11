package org.lwjgl.glfw;

public abstract class GLFWFramebufferSizeCallback implements GLFWFramebufferSizeCallbackI {

    protected GLFWFramebufferSizeCallback() {
    }

    public static GLFWFramebufferSizeCallback create(GLFWFramebufferSizeCallbackI delegate) {
        if (delegate instanceof GLFWFramebufferSizeCallback) {
            return (GLFWFramebufferSizeCallback) delegate;
        }
        return new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                delegate.invoke(window, width, height);
            }
        };
    }

    public static GLFWFramebufferSizeCallback createSafe(long handle) {
        return null;
    }

    public GLFWFramebufferSizeCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
