package org.lwjgl.glfw;

public abstract class GLFWKeyCallback implements GLFWKeyCallbackI {

    protected GLFWKeyCallback() {
    }

    public static GLFWKeyCallback create(GLFWKeyCallbackI delegate) {
        if (delegate instanceof GLFWKeyCallback) {
            return (GLFWKeyCallback) delegate;
        }
        return new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                delegate.invoke(window, key, scancode, action, mods);
            }
        };
    }

    public static GLFWKeyCallback createSafe(long handle) {
        return null;
    }

    public GLFWKeyCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
