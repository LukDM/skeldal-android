package cz.gates.skeldal.touch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cz.gates.skeldal.R
import cz.gates.skeldal.settings.GameSettings
import cz.gates.skeldal.settings.SettingsStore

/**
 * Lets the user drag the movement pad and the button column to where they want them.
 * Positions are stored as fractions of the screen, so they work on any resolution.
 */
class TouchLayoutEditorActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var editor: EditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val s = store.load()
        editor = EditorView(this, s)

        val d = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        fun btn(label: Int, onClick: () -> Unit) = Button(this).apply {
            text = getString(label)
            isAllCaps = false
            background = getDrawable(R.drawable.launcher_button)
            setTextColor(getColorStateList(R.color.launcher_button_text))
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        }
        bar.addView(btn(R.string.layout_save) { save() }, lp)
        bar.addView(btn(R.string.layout_reset) { editor.resetPositions() }, lp)
        bar.addView(btn(android.R.string.cancel) { finish() }, lp)

        // size of the selected item: [-]  "Šipky: 100 %"  [+]
        val sizeLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#FFE8D9A8"))
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = (170 * d).toInt()
        }
        val sizeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (8 * d).toInt())
            addView(btn(R.string.layout_smaller) { editor.changeSize(-10) }, lp)
            addView(sizeLabel)
            addView(btn(R.string.layout_bigger) { editor.changeSize(+10) }, lp)
        }
        editor.onSelectionChanged = { name, percent ->
            sizeLabel.text = if (name == null) getString(R.string.layout_select) else getString(R.string.layout_size, name, percent)
            sizeBar.alpha = if (name == null) 0.5f else 1f
        }
        editor.onSelectionChanged?.invoke(null, 0)

        val hint = TextView(this).apply {
            text = getString(R.string.layout_hint)
            setTextColor(Color.parseColor("#FFBFB59A"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bar)
            addView(hint)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(sizeBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    private fun save() {
        val (pad, buttons) = editor.positions()
        store.save(store.load().copy(
            touchPadX = pad?.first ?: -1f, touchPadY = pad?.second ?: -1f,
            touchButtonPositions = buttons,
            touchPadSize = editor.padSize,
            touchButtonSizes = editor.buttonSizes(),
        ))
        finish()
    }

    @SuppressLint("ViewConstructor")
    private class EditorView(context: Context, private val settings: GameSettings) : View(context) {
        private var pad: Pair<Float, Float>? = settings.padCenter
        private val buttons = LinkedHashMap(settings.touchButtonPositions)
        var padSize = settings.touchPadSize
            private set
        private val sizes = LinkedHashMap(settings.touchButtonSizes)
        /** selected item for the size buttons: pad or a single button */
        private var selectedPad = false
        private var selected: GameAction? = null
        var onSelectionChanged: ((String?, Int) -> Unit)? = null

        fun buttonSizes(): Map<GameAction, Int> = sizes.filterValues { it != 100 }

        fun changeSize(delta: Int) {
            val a = selected
            if (selectedPad) padSize = (padSize + delta).coerceIn(TouchLayout.MIN_ITEM_SIZE, TouchLayout.MAX_ITEM_SIZE)
            else if (a != null) sizes[a] = ((sizes[a] ?: 100) + delta).coerceIn(TouchLayout.MIN_ITEM_SIZE, TouchLayout.MAX_ITEM_SIZE)
            else return
            relayout()
            notifySelection()
        }

        private fun notifySelection() {
            val a = selected
            when {
                selectedPad -> onSelectionChanged?.invoke(context.getString(R.string.layout_pad), padSize)
                a != null -> onSelectionChanged?.invoke(a.label, sizes[a] ?: 100)
                else -> onSelectionChanged?.invoke(null, 0)
            }
        }
        private var controls: List<TouchLayout.Control> = emptyList()
        /** what is being dragged: null = the pad group, else a single button */
        private var dragAction: GameAction? = null
        private var draggingPad = false
        private var grabDx = 0f
        private var grabDy = 0f
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 48, 36) }
        private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 110, 60) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.rgb(232, 217, 168)
        }
        private val frame = Paint().apply { color = Color.rgb(28, 28, 32) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 217, 168); textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        private val tmp = RectF()

        fun positions(): Pair<Pair<Float, Float>?, Map<GameAction, Pair<Float, Float>>> = pad to buttons.toMap()

        fun resetPositions() {
            pad = null; buttons.clear(); padSize = 100; sizes.clear()
            relayout(); notifySelection()
        }

        private fun relayout() {
            val base = settings.touchOptions()
            val o = base.copy(padCenter = pad, buttonCenters = buttons.filterKeys { it in base.buttons },
                padSizePercent = padSize, buttonSizes = sizes.toMap())
            controls = TouchLayout.compute(width, height, density, o)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

        override fun onDraw(canvas: Canvas) {
            // where the 4:3 game picture will be (default scaling)
            val gw = minOf(width.toFloat(), height * 4f / 3f)
            val gh = gw * 3f / 4f
            canvas.drawRect((width - gw) / 2, (height - gh) / 2, (width + gw) / 2, (height + gh) / 2, frame)
            val r = 10 * density
            for (c in controls) {
                tmp.set(c.rect.left, c.rect.top, c.rect.right, c.rect.bottom)
                val isDragged = (selectedPad && TouchLayout.groupOf(c.action) == TouchLayout.Group.PAD) || c.action == selected
                canvas.drawRoundRect(tmp, r, r, if (isDragged) active else fill)
                canvas.drawRoundRect(tmp, r, r, stroke)
                text.textSize = if (c.action.label.length > 1) c.rect.height * 0.28f else c.rect.height * 0.5f
                canvas.drawText(c.action.label, c.rect.centerX, c.rect.centerY - (text.descent() + text.ascent()) / 2, text)
            }
        }

        private fun bounds(g: TouchLayout.Group): RectF? {
            val list = controls.filter { TouchLayout.groupOf(it.action) == g }
            if (list.isEmpty()) return null
            return RectF(list.minOf { it.rect.left }, list.minOf { it.rect.top }, list.maxOf { it.rect.right }, list.maxOf { it.rect.bottom })
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = TouchLayout.hit(controls, e.x, e.y) ?: return true
                    if (TouchLayout.groupOf(hit.action) == TouchLayout.Group.PAD) {
                        val b = bounds(TouchLayout.Group.PAD) ?: return true
                        draggingPad = true
                        selectedPad = true; selected = null
                        grabDx = e.x - b.centerX(); grabDy = e.y - b.centerY()
                    } else {
                        dragAction = hit.action           // single button
                        selectedPad = false; selected = hit.action
                        grabDx = e.x - hit.rect.centerX; grabDy = e.y - hit.rect.centerY
                    }
                    notifySelection()
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!draggingPad && dragAction == null) return true
                    val c = ((e.x - grabDx) / width).coerceIn(0f, 1f) to ((e.y - grabDy) / height).coerceIn(0f, 1f)
                    val a = dragAction
                    if (draggingPad) pad = c else if (a != null) buttons[a] = c
                    relayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingPad = false
                    dragAction = null
                    invalidate()
                }
            }
            return true
        }
    }
}
