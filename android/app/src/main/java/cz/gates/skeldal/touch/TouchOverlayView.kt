package cz.gates.skeldal.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Transparent view on top of the SDL surface. It receives every touch:
 *  - touches on a control send the control's key,
 *  - all other touches go through [GestureMapper] (tap = left click, hold = right click).
 * The controls are drawn here, not into the game picture.
 */
@SuppressLint("ViewConstructor")
class TouchOverlayView(
    context: Context,
    private val options: TouchLayout.Options,
    opacityPercent: Int,
    longPressRightClick: Boolean,
    private val sink: InputSink = SdlInputSink,
) : View(context) {

    /** When false the controls are hidden and inactive; gestures on the picture still work. */
    var controlsVisible = true
        set(v) {
            if (field != v) {
                field = v
                if (!v) releaseAll()
                invalidate()
            }
        }

    /** True while a game text field is active: a keyboard button is shown at the top. */
    var textInputActive = false
        set(v) {
            if (field != v) {
                field = v
                keyboardPointer = -1
                invalidate()
            }
        }

    /** Tapping the keyboard button (opens the Android keyboard again). */
    var onKeyboardButton: (() -> Unit)? = null

    /** Optional always-visible keyboard button (keyboard on / off). */
    var onKeyboardToggle: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val gestures = GestureMapper(
        sink, slopPx = 12 * density, longPressEnabled = longPressRightClick,
        schedule = { delay, block -> handler.postDelayed(block, delay) },
    )
    private var controls: List<TouchLayout.Control> = emptyList()
    private val pressed = HashMap<Int, TouchLayout.Control>()   // pointer id -> control
    private val alpha = (opacityPercent.coerceIn(10, 100) * 255 / 100)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 32, 24) }
    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 110, 60) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.rgb(232, 217, 168)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 217, 168); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val tmp = RectF()
    private val keyboardRect = RectF()
    private var keyboardPointer = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        releaseAll()
        controls = TouchLayout.compute(w, h, density, options)
        val kw = 150 * density; val kh = 40 * density
        keyboardRect.set((w - kw) / 2, 6 * density, (w + kw) / 2, 6 * density + kh)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 10 * density
        if (textInputActive) {
            val p = if (keyboardPointer >= 0) fillPressed else fill
            p.alpha = 220; stroke.alpha = 255; text.alpha = 255
            canvas.drawRoundRect(keyboardRect, radius, radius, p)
            canvas.drawRoundRect(keyboardRect, radius, radius, stroke)
            text.textSize = keyboardRect.height() * 0.42f
            val ky = keyboardRect.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(context.getString(cz.gates.skeldal.R.string.touch_keyboard), keyboardRect.centerX(), ky, text)
        }
        if (!controlsVisible) return
        for (c in controls) {
            val p = if (pressed.containsValue(c)) fillPressed else fill
            p.alpha = alpha
            stroke.alpha = alpha
            text.alpha = (alpha + 255) / 2
            tmp.set(c.rect.left, c.rect.top, c.rect.right, c.rect.bottom)
            canvas.drawRoundRect(tmp, radius, radius, p)
            canvas.drawRoundRect(tmp, radius, radius, stroke)
            text.textSize = if (c.action.label.length > 1) c.rect.height * 0.28f else c.rect.height * 0.5f
            val y = c.rect.centerY - (text.descent() + text.ascent()) / 2
            canvas.drawText(c.action.label, c.rect.centerX, y, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                onTouchStarted?.invoke()
                val x = e.getX(i); val y = e.getY(i)
                if (textInputActive && keyboardPointer < 0 && keyboardRect.contains(x, y)) {
                    keyboardPointer = id
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                    return true
                }
                val c = if (controlsVisible) TouchLayout.hit(controls, x, y) else null
                if (c != null) {
                    press(id, c)
                } else {
                    gestures.onDown(id, x, y, now)
                    if (gestures.isTracking) handler.postDelayed(longPressCheck, 520)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val pid = e.getPointerId(p)
                    if (pid != keyboardPointer && !pressed.containsKey(pid)) gestures.onMove(pid, e.getX(p), e.getY(p), now)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (id == keyboardPointer) {
                    keyboardPointer = -1
                    invalidate()
                    if (keyboardRect.contains(e.getX(i), e.getY(i))) onKeyboardButton?.invoke()
                } else if (pressed.containsKey(id)) release(id)
                else gestures.onUp(id, e.getX(i), e.getY(i), now)
            }
            MotionEvent.ACTION_CANCEL -> {
                keyboardPointer = -1
                releaseAll()
                gestures.onCancel()
            }
        }
        return true
    }

    /** Releases held keys and an unfinished gesture (activity paused, focus lost). */
    fun releaseInput() {
        releaseAll()
        gestures.onCancel()
    }

    /** Called on every new touch (used for the "automatic" visibility mode). */
    var onTouchStarted: (() -> Unit)? = null

    private val longPressCheck = Runnable { gestures.onTick(SystemClock.uptimeMillis()) }

    private fun press(id: Int, c: TouchLayout.Control) {
        pressed[id] = c
        if (c.action == GameAction.KEYBOARD) {
            onKeyboardToggle?.invoke()
        } else if (c.action.repeats) {
            tapKey(c.action)
            handler.postDelayed(repeater(id, c), REPEAT_DELAY_MS)
        } else {
            sink.keyDown(c.action.keyCode)
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    private fun release(id: Int) {
        val c = pressed.remove(id) ?: return
        if (!c.action.repeats && c.action.keyCode >= 0) sink.keyUp(c.action.keyCode)
        invalidate()
    }

    private fun releaseAll() {
        for (id in pressed.keys.toList()) release(id)
        handler.removeCallbacks(longPressCheck)
    }

    private fun tapKey(a: GameAction) {
        sink.keyDown(a.keyCode)
        sink.keyUp(a.keyCode)
    }

    /** Movement keys repeat while held, so holding "forward" keeps walking. */
    private fun repeater(id: Int, c: TouchLayout.Control): Runnable = object : Runnable {
        override fun run() {
            if (pressed[id] !== c) return
            tapKey(c.action)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 250L
    }
}
