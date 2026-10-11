package net.millida.launcher.bridge;

import org.lwjgl.system.SharedLibrary;

import java.nio.ByteBuffer;

/**
 * The game asks the window layer for the shared library behind it and keeps the
 * handle for its crash report. There is no libglfw.so here: the window comes
 * from the launcher's own bridge, so the addresses are resolved through it.
 */
public final class MillidaLibrary implements SharedLibrary {

    public static final MillidaLibrary INSTANCE = new MillidaLibrary();

    private MillidaLibrary() {
    }

    @Override
    public String getName() {
        return "millida_bridge";
    }

    @Override
    public String getPath() {
        final String dir = GlfwBridge.rendererDir();
        return dir.isEmpty() ? "millida_bridge" : dir + "/millida_bridge";
    }

    @Override
    public long address() {
        return 1L;
    }

    @Override
    public void free() {
    }

    @Override
    public long getFunctionAddress(ByteBuffer functionName) {
        final byte[] raw = new byte[functionName.remaining()];
        functionName.duplicate().get(raw);
        int length = raw.length;
        while (length > 0 && raw[length - 1] == 0) {
            length -= 1;
        }
        return GlfwBridge.procAddress(new String(raw, 0, length, java.nio.charset.StandardCharsets.US_ASCII));
    }
}
