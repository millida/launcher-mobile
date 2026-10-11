package org.lwjgl.glfw;

import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

public class GLFWImage {

    private int width;
    private int height;
    private ByteBuffer pixels;

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public ByteBuffer pixels(int capacity) {
        return pixels;
    }

    public ByteBuffer pixels() {
        return pixels;
    }

    public GLFWImage width(int value) {
        width = value;
        return this;
    }

    public GLFWImage height(int value) {
        height = value;
        return this;
    }

    public GLFWImage pixels(ByteBuffer value) {
        pixels = value;
        return this;
    }

    public GLFWImage set(int newWidth, int newHeight, ByteBuffer newPixels) {
        width = newWidth;
        height = newHeight;
        pixels = newPixels;
        return this;
    }

    public void free() {
    }

    public static GLFWImage create() {
        return new GLFWImage();
    }

    public static GLFWImage malloc() {
        return new GLFWImage();
    }

    public static GLFWImage malloc(MemoryStack stack) {
        return new GLFWImage();
    }

    public static Buffer malloc(int count) {
        return new Buffer(count);
    }

    public static Buffer malloc(int count, MemoryStack stack) {
        return new Buffer(count);
    }

    public static Buffer create(int count) {
        return new Buffer(count);
    }

    public static Buffer calloc(int count) {
        return new Buffer(count);
    }

    /**
     * The game treats the buffer as a cursor over structs: it moves the position
     * and then reads and writes fields on the buffer itself, so every accessor
     * is delegated to the element at the current position.
     */
    public static class Buffer implements AutoCloseable {
        private final GLFWImage[] images;
        private int position;

        public Buffer(int count) {
            images = new GLFWImage[Math.max(count, 0)];
            for (int i = 0; i < images.length; i++) {
                images[i] = new GLFWImage();
            }
        }

        public int limit() {
            return images.length;
        }

        public int capacity() {
            return images.length;
        }

        public int remaining() {
            return images.length - position;
        }

        public int position() {
            return position;
        }

        public Buffer position(int value) {
            position = value;
            return this;
        }

        public Buffer flip() {
            position = 0;
            return this;
        }

        public Buffer clear() {
            position = 0;
            return this;
        }

        public GLFWImage get(int index) {
            return images[index];
        }

        public GLFWImage get() {
            return images[position++];
        }

        public Buffer put(int index, GLFWImage image) {
            images[index] = image;
            return this;
        }

        private GLFWImage current() {
            final int index = Math.min(Math.max(position, 0), Math.max(images.length - 1, 0));
            return images.length == 0 ? new GLFWImage() : images[index];
        }

        public int width() {
            return current().width();
        }

        public int height() {
            return current().height();
        }

        public ByteBuffer pixels() {
            return current().pixels();
        }

        public ByteBuffer pixels(int capacity) {
            return current().pixels();
        }

        public Buffer width(int value) {
            current().width(value);
            return this;
        }

        public Buffer height(int value) {
            current().height(value);
            return this;
        }

        public Buffer pixels(ByteBuffer value) {
            current().pixels(value);
            return this;
        }

        public Buffer set(int width, int height, ByteBuffer pixels) {
            current().set(width, height, pixels);
            return this;
        }

        public void free() {
        }

        @Override
        public void close() {
            free();
        }
    }
}
