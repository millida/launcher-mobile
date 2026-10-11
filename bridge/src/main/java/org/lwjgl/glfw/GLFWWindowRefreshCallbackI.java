package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWWindowRefreshCallbackI {
    void invoke(long window);
}
