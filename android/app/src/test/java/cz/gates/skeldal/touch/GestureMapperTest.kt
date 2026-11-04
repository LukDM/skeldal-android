package cz.gates.skeldal.touch

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureMapperTest {

    private class Recorder : InputSink {
        val events = ArrayList<String>()
        override fun keyDown(keyCode: Int) { events.add("kd$keyCode") }
        override fun keyUp(keyCode: Int) { events.add("ku$keyCode") }
        override fun mouse(buttonState: Int, action: Int, x: Float, y: Float) {
            val a = when (action) { 0 -> "down"; 1 -> "up"; else -> "move" }
            events.add("$a:$buttonState@${x.toInt()},${y.toInt()}")
        }
    }

    private val rec = Recorder()
    private val delays = ArrayList<Long>()
    // runs scheduled work immediately but remembers the delay
    private val g = GestureMapper(rec, slopPx = 10f, longPressMs = 500, schedule = { d, block -> delays.add(d); block() })

    @Test
    fun tapIsLeftClick() {
        g.onDown(0, 100f, 50f, 0)
        g.onUp(0, 102f, 51f, 120)
        assertEquals(listOf("move:0@100,50", "down:1@100,50", "up:0@100,50"), rec.events)
    }

    @Test
    fun clickIsReleasedLaterSoTheGameSeesTheButtonHeld() {
        val pending = ArrayList<() -> Unit>()
        val g2 = GestureMapper(rec, slopPx = 10f, schedule = { _, block -> pending.add(block) })
        g2.onDown(0, 5f, 5f, 0)
        g2.onUp(0, 5f, 5f, 50)
        assertEquals(listOf("move:0@5,5", "down:1@5,5"), rec.events)   // still held
        pending.forEach { it() }
        assertEquals("up:0@5,5", rec.events.last())
    }

    @Test
    fun clickHoldIsLongEnough() {
        g.onDown(0, 1f, 1f, 0)
        g.onUp(0, 1f, 1f, 10)
        assertEquals(listOf(GestureMapper.CLICK_HOLD_MS), delays)
        org.junit.Assert.assertTrue(GestureMapper.CLICK_HOLD_MS >= 50)
    }

    @Test
    fun holdIsRightClick() {
        g.onDown(0, 100f, 50f, 0)
        g.onTick(300)
        g.onTick(510)
        g.onUp(0, 100f, 50f, 900)
        assertEquals(listOf("move:0@100,50", "down:2@100,50", "up:0@100,50"), rec.events)
    }

    @Test
    fun holdWithLongPressDisabledIsLeftClick() {
        g.longPressEnabled = false
        g.onDown(0, 10f, 10f, 0)
        g.onTick(800)
        g.onUp(0, 10f, 10f, 900)
        assertEquals(listOf("move:0@10,10", "down:1@10,10", "up:0@10,10"), rec.events)
    }

    @Test
    fun dragHoldsLeftButton() {
        g.onDown(0, 100f, 100f, 0)
        g.onMove(0, 105f, 100f, 10)      // inside slop: nothing
        g.onMove(0, 140f, 100f, 20)      // drag starts at the original point
        g.onMove(0, 160f, 120f, 30)
        g.onTick(1000)                    // no right click while dragging
        g.onUp(0, 160f, 120f, 1100)
        assertEquals(
            listOf("move:0@100,100", "down:1@100,100", "move:1@140,100", "move:1@160,120", "up:0@160,120"),
            rec.events
        )
    }

    @Test
    fun secondFingerIsIgnored() {
        g.onDown(0, 10f, 10f, 0)
        g.onDown(1, 300f, 300f, 5)
        g.onUp(1, 300f, 300f, 50)
        g.onUp(0, 10f, 10f, 60)
        assertEquals(listOf("move:0@10,10", "down:1@10,10", "up:0@10,10"), rec.events)
    }

    @Test
    fun cancelReleasesHeldButton() {
        g.onDown(0, 0f, 0f, 0)
        g.onMove(0, 50f, 0f, 10)
        g.onCancel()
        assertEquals("up:0@0,0", rec.events.last())
        g.onDown(0, 5f, 5f, 100)   // works again after cancel
        assertEquals("move:0@5,5", rec.events.last())
    }
}
