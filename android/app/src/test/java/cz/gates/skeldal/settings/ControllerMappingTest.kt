package cz.gates.skeldal.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ControllerMappingTest {

    /** Action names accepted by the engine (SDLContext::button_from_string in sdl_context.cpp). */
    private val engineNames = setOf(
        "disabled", "enter", "space", "ctrl", "lclick", "rclick", "ctrl+lclick", "escape", "up", "down",
        "left", "right", "turn_left", "turn_right", "merge_group", "split1", "split2", "split3", "split4",
        "split5", "split6", "map", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        "backspace", "mod_key", "slow_cursor",
    )

    @Test
    fun allOfferedActionsAreUnderstoodByTheEngine() {
        assertTrue(ControllerMapping.ACTION_NAMES.all { it in engineNames })
    }

    @Test
    fun defaultsAreValidAndComplete() {
        val d = ControllerMapping.defaults()
        assertEquals(ControllerMapping.BUTTON_COUNT, d.normal.size)
        assertTrue((d.normal + d.withMod).all { ControllerMapping.valid(it) })
        assertEquals("lclick", d.normal[0])     // A
        assertEquals("rclick", d.normal[1])     // B
        assertEquals("escape", d.normal[6])     // Start
        assertEquals("mod_key", d.normal[10])   // RB
        assertEquals(ControllerMapping.BUTTON_COUNT, ControllerMapping.BUTTON_NAMES.size)
    }

    @Test
    fun mappingSurvivesSettingsRoundTrip() {
        val m = ControllerMapping.defaults().withNormal(3, "map").withMod(0, "split2")
        val s = GameSettings(controllerMapping = m, swapSticks = true)
        assertEquals(s, GameSettings.fromMap(s.toMap()))
    }

    @Test
    fun unknownActionFallsBackToDefault() {
        val map = GameSettings().toMap().toMutableMap()
        map["ctrl_n_0"] = "fly"
        assertEquals("lclick", GameSettings.fromMap(map).controllerMapping.normal[0])
    }

    @Test
    fun iniContainsControllerSection() {
        val ini = GameConfigWriter.render(GameSettings(controllerEnabled = false), File("/g"), File("/s"))
        assertTrue(ini.contains("[controller]\nenabled=off\nswap_sticks=off\n"))
        assertTrue(ini.contains("button0=lclick\n"))
        assertTrue(ini.contains("mod+button0=F10\n"))
        assertTrue(ini.contains("button14=left\n"))
    }
}
