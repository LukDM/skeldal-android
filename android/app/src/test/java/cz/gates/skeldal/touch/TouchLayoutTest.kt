package cz.gates.skeldal.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLayoutTest {
    private val w = 2400
    private val h = 1080
    private val density = 2.75f

    private fun TouchLayout.Rect.inside() = left >= 0 && top >= 0 && right <= w && bottom <= h

    @Test
    fun defaultLayoutFitsOnScreenWithoutOverlap() {
        val c = TouchLayout.compute(w, h, density, TouchLayout.Options())
        assertEquals(6 + 4, c.size)
        assertTrue(c.all { it.rect.inside() })
        for (a in c) for (b in c) if (a !== b) {
            val overlap = a.rect.left < b.rect.right && b.rect.left < a.rect.right &&
                a.rect.top < b.rect.bottom && b.rect.top < a.rect.bottom
            assertFalse("${a.action} overlaps ${b.action}", overlap)
        }
    }

    @Test
    fun padLeftButtonsRightByDefault() {
        val c = TouchLayout.compute(w, h, density, TouchLayout.Options())
        val pad = c.first { it.action == GameAction.FORWARD }
        val menu = c.first { it.action == GameAction.MENU }
        assertTrue(pad.rect.centerX < w / 2)
        assertTrue(menu.rect.centerX > w / 2)
    }

    @Test
    fun leftHandedSwapsSides() {
        val c = TouchLayout.compute(w, h, density, TouchLayout.Options(leftHanded = true))
        assertTrue(c.first { it.action == GameAction.FORWARD }.rect.centerX > w / 2)
        assertTrue(c.first { it.action == GameAction.MENU }.rect.centerX < w / 2)
    }

    @Test
    fun sizeIsInDpAndScales() {
        val normal = TouchLayout.compute(w, h, density, TouchLayout.Options()).first().rect.width
        val big = TouchLayout.compute(w, h, density, TouchLayout.Options(sizePercent = 150)).first().rect.width
        assertEquals(TouchLayout.BUTTON_DP * density, normal, 0.01f)
        assertEquals(normal * 1.5f, big, 0.01f)
        // tablet: same physical size, not proportional to the screen
        val tablet = TouchLayout.compute(2560, 1600, 2f, TouchLayout.Options()).first().rect.width
        assertEquals(TouchLayout.BUTTON_DP * 2f, tablet, 0.01f)
    }

    @Test
    fun elementsCanBeDisabled() {
        val c = TouchLayout.compute(w, h, density, TouchLayout.Options(showDpad = false, buttons = listOf(GameAction.MENU)))
        assertEquals(listOf(GameAction.MENU), c.map { it.action })
    }

    @Test
    fun hitTest() {
        val c = TouchLayout.compute(w, h, density, TouchLayout.Options())
        val fwd = c.first { it.action == GameAction.FORWARD }.rect
        assertEquals(GameAction.FORWARD, TouchLayout.hit(c, fwd.centerX, fwd.centerY)?.action)
        assertNull(TouchLayout.hit(c, w / 2f, h / 2f))   // game picture
    }

    @Test
    fun actionsUseKeysTheGameUnderstands() {
        // Android key codes for arrows / End / PageDown / Esc / Tab / Space / Enter
        assertEquals(19, GameAction.FORWARD.keyCode)
        assertEquals(123, GameAction.STRAFE_LEFT.keyCode)
        assertEquals(93, GameAction.STRAFE_RIGHT.keyCode)
        assertEquals(111, GameAction.MENU.keyCode)
        assertEquals(61, GameAction.MAP.keyCode)
    }

    @Test
    fun customPositionsMoveWholeGroupsAndStayOnScreen() {
        val o = TouchLayout.Options(padCenter = 0.5f to 0.5f, buttonCenters = mapOf(GameAction.MENU to (1f to 0f)))
        val c = TouchLayout.compute(w, h, density, o)
        val pad = c.filter { TouchLayout.groupOf(it.action) == TouchLayout.Group.PAD }
        val cx = (pad.minOf { it.rect.left } + pad.maxOf { it.rect.right }) / 2
        val cy = (pad.minOf { it.rect.top } + pad.maxOf { it.rect.bottom }) / 2
        assertEquals(w / 2f, cx, 1f)
        assertEquals(h / 2f, cy, 1f)
        // pushed into the corner: clamped to the screen
        assertTrue(c.all { it.rect.inside() })
        val menu = c.first { it.action == GameAction.MENU }.rect
        assertEquals(0f, menu.top, 0.5f)
    }

    @Test
    fun customPositionsSurviveSettingsRoundTrip() {
        val s = cz.gates.skeldal.settings.GameSettings(touchPadX = 0.2f, touchPadY = 0.7f)
        val back = cz.gates.skeldal.settings.GameSettings.fromMap(s.toMap())
        assertEquals(0.2f to 0.7f, back.padCenter)
        assertTrue(back.touchButtonPositions.isEmpty())
    }

    @Test
    fun singleButtonMovesAloneOthersStayInColumn() {
        val normal = TouchLayout.compute(w, h, density, TouchLayout.Options())
        val moved = TouchLayout.compute(w, h, density, TouchLayout.Options(buttonCenters = mapOf(GameAction.BATTLE to (0.5f to 0.1f))))
        val battle = moved.first { it.action == GameAction.BATTLE }.rect
        assertEquals(w / 2f, battle.centerX, 1f)
        assertEquals(normal.first { it.action == GameAction.MENU }.rect.left, moved.first { it.action == GameAction.MENU }.rect.left, 0.5f)
    }

    @Test
    fun manyButtonsWrapIntoAnotherColumnAndFit() {
        val all = GameAction.DEFAULT_BUTTONS + GameAction.OPTIONAL_BUTTONS
        val c = TouchLayout.compute(w, h, density, TouchLayout.Options(buttons = all))
        assertEquals(6 + all.size, c.size)
        assertTrue(c.all { it.rect.inside() })
        assertTrue(c.filter { !it.action.repeats }.map { it.rect.left }.distinct().size >= 2)
    }

    @Test
    fun buttonPositionsAndExtrasSurviveSettingsRoundTrip() {
        val s = cz.gates.skeldal.settings.GameSettings(
            touchButtonPositions = mapOf(GameAction.SAVE to (0.9f to 0.1f)),
            touchExtraButtons = setOf(GameAction.SAVE, GameAction.LOAD),
        )
        val back = cz.gates.skeldal.settings.GameSettings.fromMap(s.toMap())
        assertEquals(s.touchButtonPositions, back.touchButtonPositions)
        assertEquals(s.touchExtraButtons, back.touchExtraButtons)
        assertTrue(GameAction.SAVE in back.touchOptions().buttons)
        assertEquals(132, GameAction.SAVE.keyCode)   // F2
    }

    @Test
    fun perItemSizesScaleOnlyThatItem() {
        val base = TouchLayout.compute(w, h, density, TouchLayout.Options())
        val o = TouchLayout.Options(padSizePercent = 150, buttonSizes = mapOf(GameAction.MAP to 200))
        val c = TouchLayout.compute(w, h, density, o)
        fun hOf(l: List<TouchLayout.Control>, a: GameAction) = l.first { it.action == a }.rect.height
        assertEquals(hOf(base, GameAction.FORWARD) * 1.5f, hOf(c, GameAction.FORWARD), 0.5f)
        assertEquals(hOf(base, GameAction.MAP) * 2f, hOf(c, GameAction.MAP), 0.5f)
        assertEquals(hOf(base, GameAction.MENU), hOf(c, GameAction.MENU), 0.5f)
        assertTrue(c.all { it.rect.inside() })
        for (a in c) for (b in c) if (a !== b) {
            val overlap = a.rect.left < b.rect.right && b.rect.left < a.rect.right &&
                a.rect.top < b.rect.bottom && b.rect.top < a.rect.bottom
            assertFalse("${a.action} overlaps ${b.action}", overlap)
        }
    }

    @Test
    fun sizesSurviveSettingsRoundTrip() {
        val s = cz.gates.skeldal.settings.GameSettings(touchPadSize = 130, touchButtonSizes = mapOf(GameAction.MENU to 70))
        assertEquals(s, cz.gates.skeldal.settings.GameSettings.fromMap(s.toMap()))
        val bad = cz.gates.skeldal.settings.GameSettings.fromMap(mapOf("touchPadSize" to 999, "touchButtonSizes" to "MENU:5;XXX:100;FORWARD:120"))
        assertEquals(TouchLayout.MAX_ITEM_SIZE, bad.touchPadSize)
        assertEquals(mapOf(GameAction.MENU to TouchLayout.MIN_ITEM_SIZE), bad.touchButtonSizes)
    }
}
