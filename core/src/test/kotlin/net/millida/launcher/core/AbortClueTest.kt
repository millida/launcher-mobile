package net.millida.launcher.core

import net.millida.launcher.core.telemetry.AbortClue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbortClueTest {

    @Test
    fun `текстовый tombstone даёт сообщение abort и первые кадры`() {
        val tombstone = """
            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            Build fingerprint: 'OnePlus/CPH2451/OP5552L1:14/UKQ1/release-keys'
            pid: 12345, tid: 12360, name: Render thread  >>> net.millida.launcher:game <<<
            signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------
            Abort message: 'EGL_BAD_SURFACE: eglSwapBuffers failed'
                x0  0000000000000000
            backtrace:
                #00 pc 000000000004d1ac  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168) (BuildId: abc)
                #01 pc 000000000001a2b0  /data/app/x/lib/arm64/libpojavexec.so (swapBuffers+64)
                #02 pc 000000000000c000  /data/app/x/lib/arm64/libpojavexec.so (swapBuffers+80)

            memory near x0:
        """.trimIndent()
        assertEquals(
            "EGL_BAD_SURFACE: eglSwapBuffers failed | libc.so!abort < libpojavexec.so!swapBuffers",
            AbortClue.summary(tombstone.toByteArray()),
        )
    }

    @Test
    fun `protobuf tombstone читается по строкам без схемы`() {
        fun field(text: String) = byteArrayOf(0x1A, text.length.toByte()) + text.toByteArray()
        val bytes = field("OnePlus/CPH2451/OP5552L1:14/UKQ1/release-keys") +
            field("Failed to create OpenGL context") +
            field("abort") + field("/apex/com.android.runtime/lib64/bionic/libc.so") +
            field("eglMakeCurrent") + field("/data/app/x/lib/arm64/libMobileGlues.so")
        val want = "Failed to create OpenGL context | libc.so!abort < libMobileGlues.so!eglMakeCurrent"
        assertEquals("строка с отпечатком сборки сообщением не считается", want, AbortClue.summary(bytes))
        val nested = byteArrayOf(0x22, (bytes.size and 0x7F or 0x80).toByte(), (bytes.size shr 7).toByte()) + bytes
        assertEquals("кадры во вложенных сообщениях, длина-байт не липнет к строке", want, AbortClue.summary(nested))
    }

    @Test
    fun `пустое и бессмысленное не пишется`() {
        assertNull(AbortClue.summary(ByteArray(0)))
        assertNull(AbortClue.summary(byteArrayOf(1, 2, 3, 0, 0, 7)))
    }
}
