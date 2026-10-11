package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWWindowPosCallbackI {
    void invoke(long window, int xpos, int ypos);
}
