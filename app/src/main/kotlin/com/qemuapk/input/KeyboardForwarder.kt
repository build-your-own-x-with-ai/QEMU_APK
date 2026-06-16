package com.qemuapk.input

import android.util.Log
import android.view.KeyEvent
import com.qemuapk.display.VncCanvasView

/**
 * Forwards keyboard events from the host Android device to the guest VM
 * via VNC key events. Maps Android keycodes to X11 keysym values.
 */
class KeyboardForwarder(
    private val vncCanvasView: VncCanvasView
) {
    companion object {
        private const val TAG = "KeyboardForwarder"

        // X11 keysym constants (subset)
        const val XK_BackSpace = 0xFF08
        const val XK_Tab = 0xFF09
        const val XK_Return = 0xFF0D
        const val XK_Escape = 0xFF1B
        const val XK_Delete = 0xFFFF
        const val XK_Home = 0xFF50
        const val XK_Left = 0xFF51
        const val XK_Up = 0xFF52
        const val XK_Right = 0xFF53
        const val XK_Down = 0xFF54
        const val XK_Page_Up = 0xFF55
        const val XK_Page_Down = 0xFF56
        const val XK_End = 0xFF57
        const val XK_Shift_L = 0xFFE1
        const val XK_Shift_R = 0xFFE2
        const val XK_Control_L = 0xFFE3
        const val XK_Control_R = 0xFFE4
        const val XK_Alt_L = 0xFFE9
        const val XK_Alt_R = 0xFFEA
        const val XK_F1 = 0xFFBE
        const val XK_F2 = 0xFFBF
        const val XK_F3 = 0xFFC0
        const val XK_F4 = 0xFFC1
        const val XK_F5 = 0xFFC2
        const val XK_F6 = 0xFFC3
        const val XK_F7 = 0xFFC4
        const val XK_F8 = 0xFFC5
        const val XK_F9 = 0xFFC6
        const val XK_F10 = 0xFFC7
        const val XK_F11 = 0xFFC8
        const val XK_F12 = 0xFFC9
    }

    /**
     * Handle a key event from the host.
     * @return true if the event was consumed
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val keySym = mapAndroidKeycodeToX11(event.keyCode)

        if (keySym != null) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            vncCanvasView.sendKeyEvent(isDown, keySym)
            Log.v(TAG, "Key ${if (isDown) "down" else "up"}: ${event.keyCode} -> keysym $keySym")
            return true
        }

        // For character input, try unicode mapping
        if (event.action == KeyEvent.ACTION_DOWN) {
            val unicodeChar = event.unicodeChar
            if (unicodeChar > 0 && unicodeChar != KeyEvent.META_SHIFT_ON) {
                vncCanvasView.sendKeyEvent(true, unicodeChar)
                vncCanvasView.sendKeyEvent(false, unicodeChar)
                return true
            }
        }

        return false
    }

    /**
     * Send a synthetic Android navigation key event to the guest.
     */
    fun sendNavigationKey(key: NavigationKey) {
        when (key) {
            NavigationKey.BACK -> {
                // Send Escape as back button
                vncCanvasView.sendKeyEvent(true, XK_Escape)
                vncCanvasView.sendKeyEvent(false, XK_Escape)
            }
            NavigationKey.HOME -> {
                // Send Home keysym
                vncCanvasView.sendKeyEvent(true, XK_Home)
                vncCanvasView.sendKeyEvent(false, XK_Home)
            }
            NavigationKey.RECENT -> {
                // No direct equivalent; send Alt+Tab
                vncCanvasView.sendKeyEvent(true, XK_Alt_L)
                vncCanvasView.sendKeyEvent(true, XK_Tab)
                vncCanvasView.sendKeyEvent(false, XK_Tab)
                vncCanvasView.sendKeyEvent(false, XK_Alt_L)
            }
            NavigationKey.POWER -> {
                // No-op for now; could send shutdown command to QEMU
            }
        }
    }

    /**
     * Map Android keycode to X11 keysym.
     * Returns null if no mapping exists.
     */
    private fun mapAndroidKeycodeToX11(androidKeycode: Int): Int? {
        return when (androidKeycode) {
            KeyEvent.KEYCODE_BACK -> XK_Escape
            KeyEvent.KEYCODE_HOME -> XK_Home
            KeyEvent.KEYCODE_DPAD_LEFT -> XK_Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> XK_Right
            KeyEvent.KEYCODE_DPAD_UP -> XK_Up
            KeyEvent.KEYCODE_DPAD_DOWN -> XK_Down
            KeyEvent.KEYCODE_DPAD_CENTER -> XK_Return
            KeyEvent.KEYCODE_ENTER -> XK_Return
            KeyEvent.KEYCODE_DEL -> XK_BackSpace
            KeyEvent.KEYCODE_FORWARD_DEL -> XK_Delete
            KeyEvent.KEYCODE_TAB -> XK_Tab
            KeyEvent.KEYCODE_SPACE -> 0x20 // space character
            KeyEvent.KEYCODE_SHIFT_LEFT -> XK_Shift_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> XK_Shift_R
            KeyEvent.KEYCODE_CTRL_LEFT -> XK_Control_L
            KeyEvent.KEYCODE_CTRL_RIGHT -> XK_Control_R
            KeyEvent.KEYCODE_ALT_LEFT -> XK_Alt_L
            KeyEvent.KEYCODE_ALT_RIGHT -> XK_Alt_R
            KeyEvent.KEYCODE_PAGE_UP -> XK_Page_Up
            KeyEvent.KEYCODE_PAGE_DOWN -> XK_Page_Down
            KeyEvent.KEYCODE_MOVE_HOME -> XK_Home
            KeyEvent.KEYCODE_MOVE_END -> XK_End
            KeyEvent.KEYCODE_F1 -> XK_F1
            KeyEvent.KEYCODE_F2 -> XK_F2
            KeyEvent.KEYCODE_F3 -> XK_F3
            KeyEvent.KEYCODE_F4 -> XK_F4
            KeyEvent.KEYCODE_F5 -> XK_F5
            KeyEvent.KEYCODE_F6 -> XK_F6
            KeyEvent.KEYCODE_F7 -> XK_F7
            KeyEvent.KEYCODE_F8 -> XK_F8
            KeyEvent.KEYCODE_F9 -> XK_F9
            KeyEvent.KEYCODE_F10 -> XK_F10
            KeyEvent.KEYCODE_F11 -> XK_F11
            KeyEvent.KEYCODE_F12 -> XK_F12
            KeyEvent.KEYCODE_ESCAPE -> XK_Escape

            // Number keys (0-9)
            KeyEvent.KEYCODE_0 -> 0x30
            KeyEvent.KEYCODE_1 -> 0x31
            KeyEvent.KEYCODE_2 -> 0x32
            KeyEvent.KEYCODE_3 -> 0x33
            KeyEvent.KEYCODE_4 -> 0x34
            KeyEvent.KEYCODE_5 -> 0x35
            KeyEvent.KEYCODE_6 -> 0x36
            KeyEvent.KEYCODE_7 -> 0x37
            KeyEvent.KEYCODE_8 -> 0x38
            KeyEvent.KEYCODE_9 -> 0x39

            // Letter keys (A-Z)
            KeyEvent.KEYCODE_A -> 0x61
            KeyEvent.KEYCODE_B -> 0x62
            KeyEvent.KEYCODE_C -> 0x63
            KeyEvent.KEYCODE_D -> 0x64
            KeyEvent.KEYCODE_E -> 0x65
            KeyEvent.KEYCODE_F -> 0x66
            KeyEvent.KEYCODE_G -> 0x67
            KeyEvent.KEYCODE_H -> 0x68
            KeyEvent.KEYCODE_I -> 0x69
            KeyEvent.KEYCODE_J -> 0x6A
            KeyEvent.KEYCODE_K -> 0x6B
            KeyEvent.KEYCODE_L -> 0x6C
            KeyEvent.KEYCODE_M -> 0x6D
            KeyEvent.KEYCODE_N -> 0x6E
            KeyEvent.KEYCODE_O -> 0x6F
            KeyEvent.KEYCODE_P -> 0x70
            KeyEvent.KEYCODE_Q -> 0x71
            KeyEvent.KEYCODE_R -> 0x72
            KeyEvent.KEYCODE_S -> 0x73
            KeyEvent.KEYCODE_T -> 0x74
            KeyEvent.KEYCODE_U -> 0x75
            KeyEvent.KEYCODE_V -> 0x76
            KeyEvent.KEYCODE_W -> 0x77
            KeyEvent.KEYCODE_X -> 0x78
            KeyEvent.KEYCODE_Y -> 0x79
            KeyEvent.KEYCODE_Z -> 0x7A

            // Common symbols
            KeyEvent.KEYCODE_COMMA -> 0x2C     // ,
            KeyEvent.KEYCODE_PERIOD -> 0x2E     // .
            KeyEvent.KEYCODE_SLASH -> 0x2F      // /
            KeyEvent.KEYCODE_BACKSLASH -> 0x5C  // \
            KeyEvent.KEYCODE_SEMICOLON -> 0x3B  // ;
            KeyEvent.KEYCODE_APOSTROPHE -> 0x27 // '
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x5B // [
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x5D // ]
            KeyEvent.KEYCODE_MINUS -> 0x2D      // -
            KeyEvent.KEYCODE_EQUALS -> 0x3D     // =
            KeyEvent.KEYCODE_GRAVE -> 0x60      // `
            KeyEvent.KEYCODE_STAR -> 0x2A       // *
            KeyEvent.KEYCODE_POUND -> 0x23      // #
            KeyEvent.KEYCODE_PLUS -> 0x2B       // +
            KeyEvent.KEYCODE_AT -> 0x40         // @

            else -> null
        }
    }
}

/**
 * Navigation keys for the floating toolbar.
 */
enum class NavigationKey {
    BACK,
    HOME,
    RECENT,
    POWER
}
