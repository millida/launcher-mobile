package org.lwjgl.glfw;

public abstract class GLFWWindowPosCallback implements GLFWWindowPosCallbackI {

    protected GLFWWindowPosCallback() {
    }

    public static GLFWWindowPosCallback create(GLFWWindowPosCallbackI delegate) {
        if (delegate instanceof GLFWWindowPosCallback) {
            return (GLFWWindowPosCallback) delegate;
        }
        return new GLFWWindowPosCallback() {
            @Override
            public void invoke(long window, int xpos, int ypos) {
                delegate.invoke(window, xpos, ypos);
            }
        };
    }

    public static GLFWWindowPosCallback createSafe(long handle) {
        return null;
    }

    public GLFWWindowPosCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
