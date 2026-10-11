package org.lwjgl.glfw;

public abstract class GLFWCharModsCallback implements GLFWCharModsCallbackI {

    protected GLFWCharModsCallback() {
    }

    public static GLFWCharModsCallback create(GLFWCharModsCallbackI delegate) {
        if (delegate instanceof GLFWCharModsCallback) {
            return (GLFWCharModsCallback) delegate;
        }
        return new GLFWCharModsCallback() {
            @Override
            public void invoke(long window, int codepoint, int mods) {
                delegate.invoke(window, codepoint, mods);
            }
        };
    }

    public static GLFWCharModsCallback createSafe(long handle) {
        return null;
    }

    public GLFWCharModsCallback set(long window) {
        GLFW.registerCallback(window, this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
