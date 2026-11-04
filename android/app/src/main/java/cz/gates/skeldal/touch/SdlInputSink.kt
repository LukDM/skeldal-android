package cz.gates.skeldal.touch

import org.libsdl.app.SDLActivity

/** Delivers overlay input to SDL exactly like a hardware keyboard / mouse would. */
object SdlInputSink : InputSink {
    override fun keyDown(keyCode: Int) = SDLActivity.onNativeKeyDown(keyCode)
    override fun keyUp(keyCode: Int) = SDLActivity.onNativeKeyUp(keyCode)
    override fun mouse(buttonState: Int, action: Int, x: Float, y: Float) =
        SDLActivity.onNativeMouse(buttonState, action, x, y, false)
}
