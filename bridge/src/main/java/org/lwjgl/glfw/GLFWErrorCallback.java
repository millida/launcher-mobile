package org.lwjgl.glfw;

import java.io.PrintStream;

public abstract class GLFWErrorCallback implements GLFWErrorCallbackI {

    protected GLFWErrorCallback() {
    }

    public static GLFWErrorCallback create(GLFWErrorCallbackI delegate) {
        if (delegate instanceof GLFWErrorCallback) {
            return (GLFWErrorCallback) delegate;
        }
        return new GLFWErrorCallback() {
            @Override
            public void invoke(int error, long description) {
                delegate.invoke(error, description);
            }
        };
    }

    public static GLFWErrorCallback createPrint(PrintStream stream) {
        return new GLFWErrorCallback() {
            @Override
            public void invoke(int error, long description) {
                stream.println("[GLFW] код ошибки " + error);
            }
        };
    }

    public static GLFWErrorCallback createThrow() {
        return new GLFWErrorCallback() {
            @Override
            public void invoke(int error, long description) {
                throw new IllegalStateException("[GLFW] код ошибки " + error);
            }
        };
    }

    public static GLFWErrorCallback createSafe(long handle) {
        return null;
    }

    public GLFWErrorCallback set() {
        GLFW.setErrorCallbackInternal(this);
        return this;
    }

    public void free() {
    }

    public long address() {
        return 0L;
    }
}
