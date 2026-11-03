package net.millida.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * From 1.9.20 to 1.9.26 the button «Обновить» did nothing: the system answered every
 * install with a confirmation to show, and the answer was addressed by an action that
 * no receiver listens to. In four days 164 of some 11 400 installs moved to a newer
 * version. Nothing here can be run without a phone, so the wiring itself is pinned.
 */
class SelfUpdateContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val installer = File("src/main/kotlin/net/millida/launcher/SelfInstaller.kt").readText()

    @Test
    fun `ответ системы об установке адресован приёмнику по классу`() {
        assertTrue(
            "приёмник должен быть объявлен в манифесте: система будит его из своего процесса",
            Regex("""<receiver\s+android:name="\.UpdateInstallReceiver"""").containsMatchIn(manifest),
        )
        assertTrue(
            "ответ сессии должен называть класс приёмника: у него нет фильтра, и по одному действию " +
                "ответ не доходит — окно подтверждения не показывается и кнопка «Обновить» ничего не делает",
            installer.contains("Intent(context, UpdateInstallReceiver::class.java)"),
        )
    }

    @Test
    fun `приложение просит право ставить пакеты`() {
        assertTrue(
            "без REQUEST_INSTALL_PACKAGES сессия установки не открывается вовсе",
            manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"),
        )
    }
}
