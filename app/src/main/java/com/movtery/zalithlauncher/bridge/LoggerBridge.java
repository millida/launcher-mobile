package com.movtery.zalithlauncher.bridge;

import androidx.annotation.Keep;

/**
 * The game's own output is captured by the native side and handed back here.
 * Same rule as with the other bridge class: package and names are a binary
 * contract with the library.
 */
@Keep
public final class LoggerBridge {

    private LoggerBridge() {
    }

    public static native void start(String filePath);

    public static native void append(String log);

    public static native void setListener(EventLogListener listener);

    public static void appendTitle(String title) {
        append("==== " + title + " ====");
    }

    @Keep
    public interface EventLogListener {
        void onEventLogged(String text);
    }
}
