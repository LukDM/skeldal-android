package cz.gates.skeldal.touch

/**
 * Game actions available on the touch overlay. Each one is delivered to the engine as the
 * keyboard key the game already understands (see game/realgame.c, game_keyboard):
 * arrows = walk/turn, End/PageDown = strafe, Esc = menu, Tab = map, Space = wall action,
 * Enter = start battle. Values are Android key codes (android.view.KeyEvent.KEYCODE_*),
 * SDL translates them to its scancodes.
 */
enum class GameAction(val keyCode: Int, val label: String, val repeats: Boolean) {
    FORWARD(19, "↑", true),        // KEYCODE_DPAD_UP
    BACK(20, "↓", true),           // KEYCODE_DPAD_DOWN
    TURN_LEFT(21, "↺", true),      // KEYCODE_DPAD_LEFT
    TURN_RIGHT(22, "↻", true),     // KEYCODE_DPAD_RIGHT
    STRAFE_LEFT(123, "←", true),   // KEYCODE_MOVE_END
    STRAFE_RIGHT(93, "→", true),   // KEYCODE_PAGE_DOWN
    MENU(111, "ESC", false),       // KEYCODE_ESCAPE (in the game: quit dialog / close window)
    MAP(61, "MAPA", false),        // KEYCODE_TAB
    ACTION(62, "AKCE", false),     // KEYCODE_SPACE
    BATTLE(66, "BOJ", false),      // KEYCODE_ENTER
    // optional buttons (off by default)
    SAVE(132, "ULOŽIT", false),    // KEYCODE_F2
    LOAD(133, "NAHRÁT", false),    // KEYCODE_F3
    INVENTORY(37, "INVENT.", false), // KEYCODE_I
    BOOK(136, "KNIHA", false),     // KEYCODE_F6
    SPELLS(137, "KOUZLA", false),  // KEYCODE_F7
    REST(134, "SPÁT", false),      // KEYCODE_F4
    SETTINGS(131, "NASTAV.", false), // KEYCODE_F1
    OVERLAY_MAP(43, "MAPA+", false), // KEYCODE_O: transparent map over the view (game mod)
    CONSOLE(68, "KONZOLE", false),  // KEYCODE_GRAVE: developer console with cheats (game mod)
    KEYBOARD(-1, "⌨", false),      // not a key: shows / hides the soft keyboard
    ;

    companion object {
        /** Buttons shown by default, in this order. */
        val DEFAULT_BUTTONS = listOf(MENU, MAP, ACTION, BATTLE)
        /** Buttons the user can add in the settings. */
        val OPTIONAL_BUTTONS = listOf(SAVE, LOAD, INVENTORY, BOOK, SPELLS, REST, SETTINGS, KEYBOARD, OVERLAY_MAP, CONSOLE)
    }
}

/** Where input ends up. On the device this is SDL (SdlInputSink); tests use a recorder. */
interface InputSink {
    fun keyDown(keyCode: Int)
    fun keyUp(keyCode: Int)

    /**
     * Mouse event in screen pixels. [buttonState] is a bit mask (1 = left, 2 = right) of the
     * buttons held *after* the event, [action] is MotionEvent.ACTION_DOWN(0) / UP(1) / MOVE(2).
     */
    fun mouse(buttonState: Int, action: Int, x: Float, y: Float)

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 2
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }
}
