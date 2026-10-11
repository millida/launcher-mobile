package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWWindowContentScaleCallbackI {
    void invoke(long window, float xscale, float yscale);
}
