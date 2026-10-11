package org.lwjgl.glfw;

public abstract class GLFWCursorPosCallback implements GLFWCursorPosCallbackI {

    protected GLFWCursorPosCallback() {
    }

    public static GLFWCursorPosCallback create(GLFWCursorPosCallbackI delegate) {
        if (delegate instanceof GLFWCursorPosCallback) {
            return (GLFWCursorPosCallback) delegate;
        }
        return new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double xpos, double ypos) {
                delegate.invoke(window, xpos, ypos);
            }
        };
    }

    public static GLFWCursorPosCallback createSafe(long handle) {
        return null;
    }

    public GLFWCursorPosCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
