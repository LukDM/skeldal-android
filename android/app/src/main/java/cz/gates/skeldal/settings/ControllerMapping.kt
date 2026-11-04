package cz.gates.skeldal.settings

/**
 * Gamepad button -> game action mapping, written to [controller] in skeldal.ini.
 * Button numbers are the SDL standard controller layout used by the engine
 * (platform/sdl/sdl_context.cpp). Action names are exactly the ones the engine parses
 * (SDLContext::button_from_string).
 */
data class ControllerMapping(val normal: List<String>, val withMod: List<String>) {

    init {
        require(normal.size == BUTTON_COUNT && withMod.size == BUTTON_COUNT)
    }

    fun withNormal(button: Int, action: String) = copy(normal = normal.toMutableList().also { it[button] = action })
    fun withMod(button: Int, action: String) = copy(withMod = withMod.toMutableList().also { it[button] = action })

    companion object {
        /** Buttons 0..22 of the engine's [controller] section (see platform/platform.h). */
        const val BUTTON_COUNT = 23
        const val LEFT_TRIGGER = 21
        const val RIGHT_TRIGGER = 22
        /** Learned extra buttons use engine buttons 23..31. */
        const val FIRST_EXTRA = 23
        const val MAX_EXTRA = 9

        /** Button names: SDL_GameControllerButton order (Xbox / PlayStation labels), then the triggers. */
        val BUTTON_NAMES = listOf(
            "A / ✕", "B / ○", "X / □", "Y / △", "Back / Share", "Guide / PS", "Start / Options",
            "L3 (levá páčka)", "R3 (pravá páčka)", "LB / L1", "RB / R1",
            "D-pad ↑", "D-pad ↓", "D-pad ←", "D-pad →",
            "Misc / Capture", "Pádlo 1", "Pádlo 2", "Pádlo 3", "Pádlo 4", "Touchpad",
            "LT / L2 (spoušť)", "RT / R2 (spoušť)",
        )

        /** Rows shown first in the editor (the rest are rare buttons). */
        val MAIN_ORDER = listOf(0, 1, 2, 3, 9, 10, LEFT_TRIGGER, RIGHT_TRIGGER, 7, 8, 4, 5, 6, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)

        /** Engine action name -> Czech description (by what the action does in the game). */
        val ACTIONS: List<Pair<String, String>> = listOf(
            "disabled" to "— nic —",
            "lclick" to "Levé tlačítko myši",
            "rclick" to "Pravé tlačítko myši (zpět)",
            "ctrl+lclick" to "Ctrl + levé tlačítko (rozdělit skupinu)",
            "enter" to "Enter (začít boj, potvrdit)",
            "space" to "Mezerník (akce na stěně, další postava)",
            "escape" to "Esc (ukončit / zavřít okno)",
            "backspace" to "Backspace (zrušit akci v boji)",
            "mod_key" to "Modifikátor (druhá vrstva tlačítek)",
            "slow_cursor" to "Pomalý kurzor (držet – přesné míření)",
            "map" to "Mapa",
            "merge_group" to "Spojit skupinu",
            "up" to "Krok vpřed",
            "down" to "Krok vzad",
            "turn_left" to "Otočit vlevo",
            "turn_right" to "Otočit vpravo",
            // engine naming: "left" sends PageDown (strafe right), "right" sends End (strafe left)
            "right" to "Úkrok vlevo",
            "left" to "Úkrok vpravo",
            "split1" to "Oddělit postavu 1",
            "split2" to "Oddělit postavu 2",
            "split3" to "Oddělit postavu 3",
            "split4" to "Oddělit postavu 4",
            "split5" to "Oddělit postavu 5",
            "split6" to "Oddělit postavu 6",
            "F1" to "F1 Nastavení",
            "F2" to "F2 Uložit hru",
            "F3" to "F3 Nahrát hru",
            "F4" to "F4 Odpočinek",
            "F5" to "F5 Automatické uložení",
            "F6" to "F6 Kniha",
            "F7" to "F7 Kouzla",
            "F8" to "F8 Útok (boj)",
            "F9" to "F9 Pohyb (boj)",
            "F10" to "F10 Výzbroj a inventář (boj)",
            "F11" to "F11 Útěk (boj)",
            "F12" to "F12 Žádná akce (boj)",
        )
        val ACTION_NAMES: Set<String> = ACTIONS.map { it.first }.toSet()

        /**
         * Defaults follow the engine's mapping (platform/sdl/input.cpp) with Android changes:
         * Start = Esc (the Guide/PS button is often taken by the system), save/load moved
         * to modifier + Back / Start, analog triggers strafe.
         */
        fun defaults() = ControllerMapping(
            normal = listOf(
                "lclick", "rclick", "enter", "space", "F1", "F1", "escape",
                "disabled", "ctrl+lclick", "backspace", "mod_key",
                "map", "merge_group", "right", "left",
                "disabled", "disabled", "disabled", "disabled", "disabled", "disabled",
                "right", "left",                      // triggers: strafe left / right
            ),
            withMod = listOf(
                "F10", "F9", "F7", "F8", "F2", "escape", "F3",
                "disabled", "disabled", "F4", "mod_key",
                "F11", "F6", "backspace", "F12",
                "disabled", "disabled", "disabled", "disabled", "disabled", "disabled",
                "right", "left",
            ),
        )

        fun valid(action: String?) = action != null && action in ACTION_NAMES
    }
}
