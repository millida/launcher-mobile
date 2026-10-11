-dontwarn com.google.errorprone.annotations.**

-keep class net.millida.launcher.runtime.RuntimeBridge { *; }
-keep class net.millida.launcher.bridge.** { *; }

-keepclassmembers class * {
    native <methods>;
}

# The window layer of the runtime looks these up by name from native code, so
# the shrinker must not rename or remove a single one of them: a missing method
# aborts the whole process while the library is loading.
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class org.lwjgl.glfw.CallbackBridge$* { *; }
-keep class com.movtery.zalithlauncher.bridge.** { *; }
-keep class com.movtery.zalithlauncher.game.input.** { *; }
-keep class com.oracle.dalvik.VMLauncher { *; }
-keep class net.millida.launcher.PackRuntime { *; }
-keep class net.millida.launcher.PackInputSink { *; }

# libSDL3.so registers natives on these classes and calls their static
# methods by name, see scripts/check_sdl_glue.py.
-keep class org.libsdl.app.** { *; }
-keepattributes *Annotation*

# What the game files and the launcher settings are read into.
-keep class net.millida.launcher.core.**$$serializer { *; }
-keepclassmembers class net.millida.launcher.core.** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class net.millida.launcher.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The voice engine calls back into these classes by name from native code.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
