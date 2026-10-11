package net.millida.launcher.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/**
 * A physical keyboard or mouse plugged into the phone. Built-in buttons and
 * the on-screen keyboard are not one: they are virtual or not alphabetic, and
 * from Android 10 the system also says whether a device is external.
 */
object DesktopInput {

    fun connected(context: Context): Boolean = devices(context).any { it.isKeyboard() || it.isMouse() }

    fun mouseConnected(context: Context): Boolean = devices(context).any { it.isMouse() }

    /**
     * Calls [onChange] on the main thread whenever a device comes or goes; the
     * result stops listening. A change of a connected device (a wireless
     * mouse reporting its battery) is not a reason to move the controls.
     */
    fun listen(context: Context, onChange: () -> Unit): () -> Unit {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return {}
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = onChange()
            override fun onInputDeviceRemoved(deviceId: Int) = onChange()
            override fun onInputDeviceChanged(deviceId: Int) = Unit
        }
        manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        return { manager.unregisterInputDeviceListener(listener) }
    }

    private fun devices(context: Context): List<InputDevice> {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return emptyList()
        return manager.inputDeviceIds.toList().mapNotNull { id -> manager.getInputDevice(id) }.filter { device -> !device.isVirtual && device.external() }
    }

    private fun InputDevice.external(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isExternal

    private fun InputDevice.isKeyboard(): Boolean = keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    private fun InputDevice.isMouse(): Boolean =
        supportsSource(InputDevice.SOURCE_MOUSE) || supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)
}
