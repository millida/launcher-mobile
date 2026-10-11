package org.lwjgl.glfw;

public abstract class GLFWMonitorCallback implements GLFWMonitorCallbackI {

    protected GLFWMonitorCallback() {
    }

    public static GLFWMonitorCallback create(GLFWMonitorCallbackI delegate) {
        if (delegate instanceof GLFWMonitorCallback) {
            return (GLFWMonitorCallback) delegate;
        }
        return new GLFWMonitorCallback() {
            @Override
            public void invoke(long monitor, int event) {
                delegate.invoke(monitor, event);
            }
        };
    }

    public static GLFWMonitorCallback createSafe(long handle) {
        return null;
    }

    public GLFWMonitorCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
