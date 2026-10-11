package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWWindowMaximizeCallbackI {
    void invoke(long window, boolean maximized);
}
