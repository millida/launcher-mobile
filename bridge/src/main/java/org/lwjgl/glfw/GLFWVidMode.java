package org.lwjgl.glfw;

public class GLFWVidMode {

    private final int width;
    private final int height;
    private final int refreshRate;

    public GLFWVidMode(int width, int height, int refreshRate) {
        this.width = width;
        this.height = height;
        this.refreshRate = refreshRate;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int refreshRate() {
        return refreshRate;
    }

    public int redBits() {
        return 8;
    }

    public int greenBits() {
        return 8;
    }

    public int blueBits() {
        return 8;
    }

    /** Same cursor-over-structs shape the game expects from the desktop library. */
    public static class Buffer {
        private final GLFWVidMode[] modes;
        private int position;

        public Buffer(GLFWVidMode[] modes) {
            this.modes = modes;
        }

        public int limit() {
            return modes.length;
        }

        public int capacity() {
            return modes.length;
        }

        public int remaining() {
            return modes.length - position;
        }

        public int position() {
            return position;
        }

        public Buffer position(int value) {
            position = value;
            return this;
        }

        public GLFWVidMode get(int index) {
            return modes[index];
        }

        public GLFWVidMode get() {
            return modes[position++];
        }

        private GLFWVidMode current() {
            final int index = Math.min(Math.max(position, 0), Math.max(modes.length - 1, 0));
            return modes[index];
        }

        public int width() {
            return current().width();
        }

        public int height() {
            return current().height();
        }

        public int refreshRate() {
            return current().refreshRate();
        }

        public int redBits() {
            return current().redBits();
        }

        public int greenBits() {
            return current().greenBits();
        }

        public int blueBits() {
            return current().blueBits();
        }
    }
}
