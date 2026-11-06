package cz.gates.skeldal

import android.os.Handler
import android.os.Looper

/** JNI functions of libskeldal.so (platform/android/app_start.cpp). Only valid in the game process. */
object NativeBridge {
    /** Extra gamepad button [index] 0..8 (engine controller button 23 + index). */
    @JvmStatic external fun nativeExtraButton(index: Int, down: Boolean)

    /** Mute / unmute the game audio (Android audio focus). */
    @JvmStatic external fun nativeSetAudioMuted(muted: Boolean)

    /** Opens the on-screen keyboard again (only does something while a text field is active). */
    @JvmStatic external fun nativeShowKeyboard()

    /** Keyboard on / off anywhere (optional touch button). */
    @JvmStatic external fun nativeToggleKeyboard()

    /** The soft keyboard was closed by the user. */
    @JvmStatic external fun nativeKeyboardHidden()

    /** Key straight into the game's queue (BIOS code), bypassing the text input filter. */
    @JvmStatic external fun nativeInjectKey(biosCode: Int)

    /** Called on the UI thread when a game text field becomes active / inactive. */
    @Volatile var textInputListener: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /** Called from native code (game thread) - see notify_text_input in app_start.cpp. */
    @JvmStatic
    fun onTextInput(active: Boolean) {
        main.post { textInputListener?.invoke(active) }
    }
}
