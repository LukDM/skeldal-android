package cz.gates.skeldal.touch

import kotlin.math.hypot

/**
 * Turns finger gestures on the game picture into mouse input for the engine:
 *  - tap                      -> left click
 *  - hold without moving      -> right click (after [longPressMs]), if enabled
 *  - drag                     -> left button held while moving (e.g. moving items)
 * The cursor follows the finger so the game shows hover effects.
 * Only one finger controls the mouse; further fingers on the picture are ignored.
 * Time is passed in explicitly so the logic can be unit tested.
 *
 * A click is released only after [clickHoldMs]: parts of the game UI (e.g. dialog buttons)
 * check whether the button is *held* when they poll the mouse, so a press and release
 * delivered at the same moment would be missed.
 */
class GestureMapper(
    private val sink: InputSink,
    private val slopPx: Float,
    private val longPressMs: Long = 500,
    var longPressEnabled: Boolean = true,
    private val clickHoldMs: Long = CLICK_HOLD_MS,
    /** Runs [block] after [delayMs] on the input thread. */
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
) {
    private enum class State { IDLE, PENDING, DRAGGING, CONSUMED }

    private var state = State.IDLE
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    val isTracking: Boolean get() = state != State.IDLE

    fun onDown(id: Int, x: Float, y: Float, time: Long) {
        if (state != State.IDLE) return
        pointerId = id
        downX = x; downY = y; downTime = time
        state = State.PENDING
        sink.mouse(0, InputSink.ACTION_MOVE, x, y)
    }

    fun onMove(id: Int, x: Float, y: Float, time: Long) {
        if (id != pointerId) return
        when (state) {
            State.PENDING -> {
                if (hypot(x - downX, y - downY) > slopPx) {
                    state = State.DRAGGING
                    sink.mouse(InputSink.BUTTON_LEFT, InputSink.ACTION_DOWN, downX, downY)
                    sink.mouse(InputSink.BUTTON_LEFT, InputSink.ACTION_MOVE, x, y)
                } else {
                    onTick(time)
                }
            }
            State.DRAGGING -> sink.mouse(InputSink.BUTTON_LEFT, InputSink.ACTION_MOVE, x, y)
            else -> {}
        }
    }

    fun onUp(id: Int, x: Float, y: Float, time: Long) {
        if (id != pointerId) return
        when (state) {
            State.PENDING -> {
                if (longPressEnabled && time - downTime >= longPressMs) {
                    rightClick(downX, downY)
                } else {
                    click(InputSink.BUTTON_LEFT, downX, downY)
                }
            }
            State.DRAGGING -> sink.mouse(0, InputSink.ACTION_UP, x, y)
            else -> {}
        }
        reset()
    }

    /** Gesture interrupted by the system (e.g. notification shade): release a held button. */
    fun onCancel() {
        if (state == State.DRAGGING) sink.mouse(0, InputSink.ACTION_UP, downX, downY)
        reset()
    }

    /** Called periodically / by a timer while a finger is down. */
    fun onTick(time: Long) {
        if (state == State.PENDING && longPressEnabled && time - downTime >= longPressMs) {
            rightClick(downX, downY)
            state = State.CONSUMED
        }
    }

    private fun rightClick(x: Float, y: Float) = click(InputSink.BUTTON_RIGHT, x, y)

    private fun click(button: Int, x: Float, y: Float) {
        sink.mouse(button, InputSink.ACTION_DOWN, x, y)
        schedule(clickHoldMs) { sink.mouse(0, InputSink.ACTION_UP, x, y) }
    }

    private fun reset() {
        state = State.IDLE
        pointerId = -1
    }

    companion object {
        /** Long enough for the game loop to see the button held (it polls every ~10-25 ms). */
        const val CLICK_HOLD_MS = 80L
    }
}
