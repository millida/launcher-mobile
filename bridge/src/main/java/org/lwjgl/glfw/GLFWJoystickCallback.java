package org.lwjgl.glfw;

public abstract class GLFWJoystickCallback implements GLFWJoystickCallbackI {

    protected GLFWJoystickCallback() {
    }

    public static GLFWJoystickCallback create(GLFWJoystickCallbackI delegate) {
        if (delegate instanceof GLFWJoystickCallback) {
            return (GLFWJoystickCallback) delegate;
        }
        return new GLFWJoystickCallback() {
            @Override
            public void invoke(int jid, int event) {
                delegate.invoke(jid, event);
            }
        };
    }

    public static GLFWJoystickCallback createSafe(long handle) {
        return null;
    }

    public GLFWJoystickCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
