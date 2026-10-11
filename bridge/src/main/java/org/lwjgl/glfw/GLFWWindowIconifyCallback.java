package org.lwjgl.glfw;

public abstract class GLFWWindowIconifyCallback implements GLFWWindowIconifyCallbackI {

    protected GLFWWindowIconifyCallback() {
    }

    public static GLFWWindowIconifyCallback create(GLFWWindowIconifyCallbackI delegate) {
        if (delegate instanceof GLFWWindowIconifyCallback) {
            return (GLFWWindowIconifyCallback) delegate;
        }
        return new GLFWWindowIconifyCallback() {
            @Override
            public void invoke(long window, boolean iconified) {
                delegate.invoke(window, iconified);
            }
        };
    }

    public static GLFWWindowIconifyCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowIconifyCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
