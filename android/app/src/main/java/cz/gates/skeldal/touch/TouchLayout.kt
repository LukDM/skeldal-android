package cz.gates.skeldal.touch

/** Placement of the on-screen controls. Pure geometry, no Android dependencies. */
object TouchLayout {

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val width get() = right - left
        val height get() = bottom - top
    }

    data class Control(val action: GameAction, val rect: Rect)

    data class Options(
        val sizePercent: Int = 100,
        val leftHanded: Boolean = false,
        val centered: Boolean = false,
        val showDpad: Boolean = true,
        val buttons: List<GameAction> = listOf(GameAction.MENU, GameAction.MAP, GameAction.ACTION, GameAction.BATTLE),
        /** Custom center of the movement pad as a fraction of the screen (0..1), null = default place. */
        val padCenter: Pair<Float, Float>? = null,
        /** Custom centers of single buttons (fractions of the screen); others stay in the column. */
        val buttonCenters: Map<GameAction, Pair<Float, Float>> = emptyMap(),
        /** Size of the movement pad in % of the global size (50..250). */
        val padSizePercent: Int = 100,
        /** Size of single buttons in % of the global size (50..250); missing = 100. */
        val buttonSizes: Map<GameAction, Int> = emptyMap(),
    )

    const val MIN_ITEM_SIZE = 50
    const val MAX_ITEM_SIZE = 250

    enum class Group { PAD, BUTTONS }

    fun groupOf(a: GameAction): Group = if (a.repeats) Group.PAD else Group.BUTTONS

    /** Top-left corner for a box of size w x h centered at a fraction of the screen, kept on screen. */
    private fun place(center: Pair<Float, Float>, w: Float, h: Float, width: Int, height: Int): Pair<Float, Float> {
        val x = (center.first * width - w / 2).coerceIn(0f, (width - w).coerceAtLeast(0f))
        val y = (center.second * height - h / 2).coerceIn(0f, (height - h).coerceAtLeast(0f))
        return x to y
    }

    /** Base size of one button in dp; the size is in dp so it does not grow on tablets. */
    const val BUTTON_DP = 56f
    const val GAP_DP = 6f
    const val MARGIN_DP = 16f

    fun compute(width: Int, height: Int, density: Float, o: Options): List<Control> {
        val scale = o.sizePercent.coerceIn(50, 200) / 100f
        val b = BUTTON_DP * density * scale
        val gap = GAP_DP * density * scale
        val margin = MARGIN_DP * density
        val out = ArrayList<Control>()

        // movement pad: 3 x 2 like the arrows of the original game
        //   ↺ ↑ ↻
        //   ← ↓ →
        if (o.showDpad) {
            val ps = o.padSizePercent.coerceIn(MIN_ITEM_SIZE, MAX_ITEM_SIZE) / 100f
            val pb = b * ps
            val pg = gap * ps
            val padW = 3 * pb + 2 * pg
            val padH = 2 * pb + pg
            var left = if (o.leftHanded) width - margin - padW else margin
            var top = if (o.centered) (height - padH) / 2 else height - margin - padH
            o.padCenter?.let { c -> place(c, padW, padH, width, height).let { left = it.first; top = it.second } }
            val grid = listOf(
                listOf(GameAction.TURN_LEFT, GameAction.FORWARD, GameAction.TURN_RIGHT),
                listOf(GameAction.STRAFE_LEFT, GameAction.BACK, GameAction.STRAFE_RIGHT),
            )
            for ((row, actions) in grid.withIndex()) {
                for ((col, a) in actions.withIndex()) {
                    val x = left + col * (pb + pg)
                    val y = top + row * (pb + pg)
                    out.add(Control(a, Rect(x, y, x + pb, y + pb)))
                }
            }
        }

        // buttons: columns on the opposite side (a further column when they do not fit the height);
        // buttons moved in the editor are placed on their own
        fun hOf(a: GameAction) = b * (o.buttonSizes[a] ?: 100).coerceIn(MIN_ITEM_SIZE, MAX_ITEM_SIZE) / 100f
        val inColumn = o.buttons.filter { it !in o.buttonCenters }
        if (inColumn.isNotEmpty()) {
            // split into columns by the real height of the buttons
            val avail = height - 2 * margin
            val columns = ArrayList<MutableList<GameAction>>()
            var used = 0f
            for (a in inColumn) {
                val h = hOf(a)
                if (columns.isEmpty() || (columns.last().isNotEmpty() && used + gap + h > avail)) {
                    columns.add(ArrayList()); used = h
                } else {
                    used += gap + h
                }
                columns.last().add(a)
            }
            var offset = 0f
            for (col in columns) {
                val colW = col.maxOf { hOf(it) * 1.4f }
                val colH = col.sumOf { hOf(it).toDouble() }.toFloat() + (col.size - 1) * gap
                val right = if (o.leftHanded) margin + offset + colW else width - margin - offset
                var y = if (o.centered) (height - colH) / 2 else height - margin - colH
                for (a in col) {
                    val h = hOf(a); val w = h * 1.4f
                    // buttons of one column are aligned to the screen edge
                    val left = if (o.leftHanded) right - colW else right - w
                    out.add(Control(a, Rect(left, y, left + w, y + h)))
                    y += h + gap
                }
                offset += colW + gap
            }
        }
        for (a in o.buttons) {
            val c = o.buttonCenters[a] ?: continue
            val h = hOf(a); val w = h * 1.4f
            val (x, y) = place(c, w, h, width, height)
            out.add(Control(a, Rect(x, y, x + w, y + h)))
        }
        return out
    }

    fun hit(controls: List<Control>, x: Float, y: Float): Control? = controls.lastOrNull { it.rect.contains(x, y) }
}
