package net.millida.launcher.core

import net.millida.launcher.core.telemetry.AnrTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrTraceTest {
    private val dump = """
        ----- pid 4242 at 2026-10-13 12:13:07 -----
        Cmd line: net.millida.launcher
        "Signal Catcher" daemon prio=10 tid=6 Runnable
          native: #00 pc 0000000000 /apex/com.android.art/lib64/libart.so (art::DumpNativeStack+92)

        "main" prio=5 tid=1 Native
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a0 self=0xb400
          | sysTid=4242 nice=-10 cgrp=top-app sched=0/0 handle=0x7d
          native: #00 pc 000000000004d1ac  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
          native: #01 pc 0000000000052a10  /apex/com.android.runtime/lib64/bionic/libc.so (pthread_mutex_lock+208)
          native: #02 pc 0000000000012345  /data/app/~~x/net.millida.launcher/lib/arm64/libpojavexec.so (Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorPos+64)
          at org.lwjgl.glfw.CallbackBridge.nativeSendCursorPos(Native method)
          at net.millida.launcher.PackInputSink.move(PackInputSink.kt:88)
          at android.view.View.dispatchTouchEvent(View.java:15050)

        "RenderThread" daemon prio=7 tid=22 Native
          at android.os.MessageQueue.nativePollOnce(Native method)
    """.trimIndent()

    @Test
    fun `the top of the main thread names the call that held it`() {
        val stack = AnrTrace.mainStack(dump)!!
        assertTrue(stack, stack.startsWith("Native: libc.so!syscall < libc.so!pthread_mutex_lock < libpojavexec.so!Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorPos"))
        assertTrue(stack, stack.contains("net.millida.launcher.PackInputSink.move:88"))
        assertTrue("чужой поток в стек не попадает: $stack", !stack.contains("nativePollOnce") && !stack.contains("DumpNativeStack"))
    }

    @Test
    fun `a dump without the main thread gives nothing and a long one is cut`() {
        assertNull(AnrTrace.mainStack("\"Worker\" prio=5 tid=9 Runnable\n  at a.B.c(B.kt:1)"))
        assertNull(AnrTrace.mainStack(null))
        val long = "\"main\" prio=5 tid=1 Blocked\n" + (1..40).joinToString("\n") { "  at very.long.pkg.name.number$it.SomeClassWithLongName.method(File.kt:$it)" }
        assertEquals(AnrTrace.MAX, AnrTrace.mainStack(long)!!.length)
        assertEquals("Waiting", AnrTrace.mainStack("\"main\" prio=5 tid=1 Waiting\n  | group=\"main\"\n"))
    }

    private fun frame(owner: String, method: String, line: Int) = StackTraceElement(owner, method, "File.kt", line)

    // What the screen thread looks like when a touch waits for SDL's lock (line -2 is a native method).
    @Test
    fun `a thread read from inside the process is told by short names`() {
        val trace = arrayOf(
            frame("org.libsdl.app.SDLActivity", "onNativeMouse", -2),
            frame("net.millida.launcher.SdlInputSink", "cursorPosition", 74),
            frame("net.millida.launcher.runtime.GameSurfaceView", "onTouchEvent", 210),
            frame("android.view.View", "dispatchTouchEvent", 15000),
            frame("android.view.View", "dispatchTouchEvent", 15000),
            frame("android.os.Looper", "loop", 317),
        )
        assertEquals(
            "SDLActivity.onNativeMouse(native) < SdlInputSink.cursorPosition:74 < GameSurfaceView.onTouchEvent:210" +
                " < View.dispatchTouchEvent:15000 < Looper.loop:317",
            AnrTrace.ofThread(trace),
        )
        assertEquals("SDLActivity.onNativeMouse", AnrTrace.culprit(trace))
    }

    @Test
    fun `the culprit is the first call that is not the platform's`() {
        val trace = arrayOf(
            frame("java.io.FileOutputStream", "writeBytes", -2),
            frame("kotlin.io.FilesKt__FileReadWriteKt", "appendText", 120),
            frame("net.millida.launcher.runtime.GameSession", "note", 38),
            frame("android.os.Handler", "dispatchMessage", 106),
        )
        assertEquals("GameSession.note", AnrTrace.culprit(trace))
        val idle = arrayOf(frame("android.os.MessageQueue", "nativePollOnce", -2), frame("android.os.Looper", "loop", 300))
        assertEquals("MessageQueue.nativePollOnce", AnrTrace.culprit(idle))
    }

    @Test
    fun `an empty stack gives nothing and a deep one fits an event`() {
        assertNull(AnrTrace.ofThread(emptyArray()))
        assertNull(AnrTrace.ofThread(null))
        assertNull(AnrTrace.culprit(null))
        val deep = Array(60) { frame("net.millida.launcher.SomeVeryLongClassNameForTheTest", "method$it", it + 1) }
        assertTrue(AnrTrace.ofThread(deep)!!.length <= AnrTrace.THREAD_MAX)
    }
}
