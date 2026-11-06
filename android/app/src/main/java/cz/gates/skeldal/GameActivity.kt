package cz.gates.skeldal

import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import cz.gates.skeldal.install.InstallationRepository
import cz.gates.skeldal.settings.ExtraButtons
import cz.gates.skeldal.settings.GameSettings
import cz.gates.skeldal.settings.SettingsStore
import cz.gates.skeldal.touch.TouchOverlayView
import org.libsdl.app.SDLActivity

/**
 * Hosts the SDL2 surface. The native game library is libskeldal.so (built from the repository root),
 * its entry point is SDL_main in platform/android/app_start.cpp.
 * A [TouchOverlayView] on top provides the touch controls.
 */
class GameActivity : SDLActivity() {

    private var overlay: TouchOverlayView? = null
    /** extra gamepad button key code -> slot passed to the engine */
    private var extraSlots: Map<Int, Int> = emptyMap()
    private var touchMode = GameSettings.TouchMode.AUTO

    override fun getLibraries(): Array<String> = arrayOf("SDL2", "skeldal")

    /** Passed to SDL_main as argv: the game language (platform/android/app_start.cpp). */
    override fun getArguments(): Array<String> {
        val folder = SettingsStore(this).load().language.folder ?: return emptyArray()
        val available = InstallationRepository(filesDir).load()?.languages ?: emptyList()
        return if (available.any { it.equals(folder, ignoreCase = true) }) arrayOf("-l", folder) else emptyArray()
    }

    override fun getMainSharedObject(): String =
        applicationInfo.nativeLibraryDir + "/libskeldal.so"

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // short interruptions (notifications, navigation) only lower the volume
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> setMuted(true)
                    AudioManager.AUDIOFOCUS_GAIN -> setMuted(false)
                }
            }
            .build()
    }

    private fun setMuted(muted: Boolean) {
        try {
            NativeBridge.nativeSetAudioMuted(muted)
        } catch (e: UnsatisfiedLinkError) {
            // native library not loaded (SDL failed to start) - nothing to mute
        }
    }

    override fun onResume() {
        super.onResume()
        // lifecycle of the game itself (pause/resume, audio) is handled by SDL and the engine
        if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setMuted(false)
        }
    }

    override fun onPause() {
        overlay?.releaseInput()
        super.onPause()
    }

    override fun onDestroy() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        NativeBridge.textInputListener = null
        super.onDestroy()
    }

    @Volatile private var allowPortrait = true
    private var imeShown = false

    /** Tells the engine when the user closes the soft keyboard (ends the free typing mode). */
    private fun watchKeyboard() {
        val root = window.decorView
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val shown = if (android.os.Build.VERSION.SDK_INT >= 30) {
                root.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            } else {
                val r = android.graphics.Rect()
                root.getWindowVisibleDisplayFrame(r)
                root.height - r.bottom > root.height * 0.15
            }
            if (imeShown && !shown) NativeBridge.nativeKeyboardHidden()
            imeShown = shown
        }
    }

    private fun orientation() =
        if (allowPortrait) ActivityInfo.SCREEN_ORIENTATION_FULL_USER   // respects the system rotation lock
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    /** SDL requests an orientation when it creates the window; the launcher setting decides instead. */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String) {
        runOnUiThread { requestedOrientation = orientation() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        val s = SettingsStore(this).load()
        touchMode = s.touchMode
        allowPortrait = s.allowPortrait
        requestedOrientation = orientation()
        if (s.controllerEnabled) extraSlots = ExtraButtons.slots(s.extraButtons)

        val view = TouchOverlayView(
            this,
            s.touchOptions(),
            opacityPercent = s.touchOpacity,
            longPressRightClick = s.longPressRightClick,
        )
        view.controlsVisible = when (touchMode) {
            GameSettings.TouchMode.ALWAYS -> true
            GameSettings.TouchMode.NEVER -> false
            // shown at start; hidden as soon as a physical gamepad/keyboard is actually used
            // (not just "connected" - some phones report built-in devices as joysticks)
            GameSettings.TouchMode.AUTO -> true
        }
        view.onTouchStarted = {
            if (touchMode == GameSettings.TouchMode.AUTO) view.controlsVisible = true
        }
        view.onKeyboardButton = { NativeBridge.nativeShowKeyboard() }
        view.onKeyboardToggle = { NativeBridge.nativeToggleKeyboard() }
        view.onInjectKey = { NativeBridge.nativeInjectKey(it) }
        watchKeyboard()
        NativeBridge.textInputListener = { active -> overlay?.textInputActive = active }
        addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = view
    }

    // Automatic mode: hide the touch controls when a physical gamepad or keyboard is used.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Soft keyboards send digits as key events; SDL turns them into text with the key character
        // map of the system keyboard layout, which on a Czech layout gives "+ěšč…" for 1 2 3 4 and
        // the digits for "!@#$". Digits from a virtual keyboard therefore go straight to the game.
        if (isSoftKeyboard(event)) {
            val ch = softDigit(event)
            if (ch != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) NativeBridge.nativeInjectKey(ch)
                return true
            }
        }
        val physical = isPhysicalInput(event.device, event.source)
        if (physical) hideForHardwareInput()
        // extra buttons (M1, M2, ...) go to the engine as controller buttons 23..31
        val slot = extraSlots[event.keyCode]
        if (slot != null && event.device?.isVirtual == false) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) NativeBridge.nativeExtraButton(slot, true)
                KeyEvent.ACTION_UP -> NativeBridge.nativeExtraButton(slot, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) overlay?.releaseInput()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_JOYSTICK) || ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            hideForHardwareInput()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun hideForHardwareInput() {
        if (touchMode == GameSettings.TouchMode.AUTO) overlay?.controlsVisible = false
    }

    private fun isSoftKeyboard(e: KeyEvent): Boolean =
        e.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD || e.device?.isVirtual == true

    /** BIOS code of a digit / shifted digit symbol (US layout like the game), 0 = not such a key. */
    private fun softDigit(e: KeyEvent): Int {
        val k = e.keyCode
        val digit = when (k) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> return '0'.code + (k - KeyEvent.KEYCODE_NUMPAD_0)
            else -> return 0
        }
        if (!e.isShiftPressed) return '0'.code + digit
        return ")!@#$%^&*("[digit].code
    }

    private fun isPhysicalInput(device: InputDevice?, source: Int): Boolean {
        if (device == null || device.isVirtual) return false
        val gamepad = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        val keyboard = device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        return gamepad || keyboard
    }
}
