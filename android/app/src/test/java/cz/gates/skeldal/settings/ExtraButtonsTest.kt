package cz.gates.skeldal.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtraButtonsTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val m = mapOf(188 to "F2", 189 to "lclick")   // KEYCODE_BUTTON_1 / BUTTON_2
        assertEquals(m, ExtraButtons.decode(ExtraButtons.encode(m)))
    }

    @Test
    fun invalidEntriesAreDropped() {
        assertEquals(mapOf(188 to "F2", 190 to "mod_key"), ExtraButtons.decode("188:F2,96:F3,abc:F1,190:mod_key,191:fly,192"))
    }

    @Test
    fun standardButtonsAndTriggerKeysCannotBeExtra() {
        assertFalse(ExtraButtons.isAssignable(96))   // BUTTON_A
        assertFalse(ExtraButtons.isAssignable(19))   // DPAD_UP
        assertFalse(ExtraButtons.isAssignable(104))  // BUTTON_L2 -> trigger row
        assertTrue(ExtraButtons.isAssignable(188))   // BUTTON_1
    }

    @Test
    fun everyEngineActionIsOffered() {
        val names = ExtraButtons.ACTIONS.map { it.first }
        assertTrue("lclick" in names && "mod_key" in names && "slow_cursor" in names && "F3" in names)
    }

    @Test
    fun slotsAreStableAndLimited() {
        val m = (200 until 212).associateWith { "F1" }
        val slots = ExtraButtons.slots(m)
        assertEquals(ControllerMapping.MAX_EXTRA, slots.size)
        assertEquals(0, slots[200]); assertEquals(8, slots[208])
        assertEquals(slots, ExtraButtons.slots(m.entries.reversed().associate { it.key to it.value }))
    }

    @Test
    fun extrasAreWrittenAsEngineButtons23Plus() {
        val s = GameSettings(extraButtons = mapOf(250 to "F3", 189 to "slow_cursor"), slowCursorWithMod = false)
        assertEquals(s, GameSettings.fromMap(s.toMap()))
        val ini = GameConfigWriter.render(s, File("/g"), File("/s"))
        assertTrue(ini.contains("button23=slow_cursor\nmod+button23=slow_cursor\n"))   // 189 sorts first
        assertTrue(ini.contains("button24=F3\nmod+button24=F3\n"))
        assertTrue(ini.contains("slow_cursor_with_mod=off\n"))
    }

    @Test
    fun triggersStrafeByDefault() {
        val d = ControllerMapping.defaults()
        assertEquals("right", d.normal[ControllerMapping.LEFT_TRIGGER])   // End = strafe left
        assertEquals("left", d.normal[ControllerMapping.RIGHT_TRIGGER])   // PageDown = strafe right
        assertEquals(ControllerMapping.BUTTON_COUNT, ControllerMapping.MAIN_ORDER.distinct().size)
    }

    @Test
    fun oldSettingsWithFifteenButtonsAreMigrated() {
        // settings saved by the previous version only had buttons 0..14
        val old = GameSettings().toMap().filterKeys { k ->
            !(k.startsWith("ctrl_") && k.substringAfterLast('_').toInt() >= 15)
        }
        val s = GameSettings.fromMap(old)
        assertEquals("right", s.controllerMapping.normal[ControllerMapping.LEFT_TRIGGER])
    }
}
