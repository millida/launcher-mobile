package net.millida.launcher

import com.movtery.zalithlauncher.bridge.ZLNativeInvoker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ExitTrapContractTest {

    @Test
    fun `нативная сторона находит метод, которому сообщает о конце игры`() {
        val method = runCatching {
            ZLNativeInvoker::class.java.getMethod("jvmExit", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }.getOrNull()

        assertNotNull(
            "libpojavexec ищет ZLNativeInvoker.jvmExit(int, boolean) при старте игры: без него аборт Java-машины " +
                "некому принять, и игра остаётся чёрным экраном со звуком",
            method,
        )
        assertTrue("метод вызывается как статический", Modifier.isStatic(method!!.modifiers))
        assertEquals("подпись (IZ)V зашита в нативной библиотеке", Void.TYPE, method.returnType)
    }
}
