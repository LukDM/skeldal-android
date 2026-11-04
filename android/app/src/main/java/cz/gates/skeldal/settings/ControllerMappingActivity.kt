package cz.gates.skeldal.settings

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import cz.gates.skeldal.R

/**
 * Which game action each gamepad button triggers (with and without the modifier),
 * plus extra buttons outside the standard layout (learned by pressing them).
 */
class ControllerMappingActivity : Activity() {

    private lateinit var store: SettingsStore
    private val normalSpinners = ArrayList<Spinner>()
    private val modSpinners = ArrayList<Spinner>()
    private val actionNames = ControllerMapping.ACTIONS.map { it.first }
    private val extraNames = ExtraButtons.ACTIONS.map { it.first }
    private lateinit var extraContainer: LinearLayout
    private lateinit var slowSwitch: Switch
    private var loading = false
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val pad = (24 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(text(getString(R.string.mapping_title), 24f, "#FFE8D9A8"))
        root.addView(text(getString(R.string.mapping_header), 13f, "#FF9C9178"))

        val labels = ControllerMapping.ACTIONS.map { it.second }
        // spinners are kept in button order; rows are displayed in a friendlier order
        val rows = arrayOfNulls<View>(ControllerMapping.BUTTON_COUNT)
        for (i in 0 until ControllerMapping.BUTTON_COUNT) {
            val row = row()
            row.addView(text(ControllerMapping.BUTTON_NAMES[i], 15f, "#FFE0D6BC"), weight(0.8f))
            val n = spinner(labels); val m = spinner(labels)
            row.addView(n, weight(1.6f)); row.addView(m, weight(1.6f))
            normalSpinners.add(n); modSpinners.add(m)
            rows[i] = row
        }
        for (i in ControllerMapping.MAIN_ORDER) root.addView(rows[i])

        slowSwitch = Switch(this).apply {
            text = getString(R.string.mapping_slow_with_mod)
            textSize = 15f
            setTextColor(Color.parseColor("#FFE0D6BC"))
            minimumHeight = (48 * density).toInt()
            foreground = getDrawable(R.drawable.focus_frame)
            setOnCheckedChangeListener { _, v -> if (!loading) store.save(store.load().copy(slowCursorWithMod = v)) }
        }
        root.addView(slowSwitch)
        root.addView(text(getString(R.string.mapping_slow_hint), 13f, "#FF9C9178"))

        root.addView(text(getString(R.string.mapping_extra_title), 18f, "#FFE8D9A8").apply {
            setPadding(0, (20 * density).toInt(), 0, 0)
        })
        root.addView(text(getString(R.string.mapping_extra_hint), 13f, "#FF9C9178"))
        extraContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(extraContainer)
        root.addView(button(getString(R.string.mapping_extra_add)) { learnButton() })

        root.addView(button(getString(R.string.mapping_reset)) {
            store.save(store.load().copy(
                controllerMapping = ControllerMapping.defaults(), slowCursorWithMod = true, extraButtons = emptyMap()))
            show()
        })
        root.addView(button(getString(R.string.btn_close)) { finish() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF1B1410"))
            addView(root)
        })
        show()
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = saveMain()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        (normalSpinners + modSpinners).forEach { it.onItemSelectedListener = listener }
        normalSpinners.first().requestFocus()
    }

    private fun show() {
        loading = true
        val s = store.load()
        for (i in 0 until ControllerMapping.BUTTON_COUNT) {
            normalSpinners[i].setSelection(actionNames.indexOf(s.controllerMapping.normal[i]).coerceAtLeast(0))
            modSpinners[i].setSelection(actionNames.indexOf(s.controllerMapping.withMod[i]).coerceAtLeast(0))
        }
        slowSwitch.isChecked = s.slowCursorWithMod
        showExtras(s.extraButtons)
        loading = false
    }

    private fun showExtras(extras: Map<Int, String>) {
        extraContainer.removeAllViews()
        val labels = ExtraButtons.ACTIONS.map { it.second }
        for ((code, action) in extras) {
            val r = row()
            r.addView(text(keyName(code), 15f, "#FFE0D6BC"), weight(0.8f))
            val sp = spinner(labels)
            sp.setSelection(extraNames.indexOf(action).coerceAtLeast(0), false)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val s = store.load()
                    store.save(s.copy(extraButtons = s.extraButtons + (code to extraNames[pos])))
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            r.addView(sp, weight(2.2f))
            r.addView(button(getString(R.string.mapping_extra_remove)) {
                val s = store.load()
                store.save(s.copy(extraButtons = s.extraButtons - code))
                showExtras(store.load().extraButtons)
            }, weight(1.0f))
            extraContainer.addView(r)
        }
    }

    /** Waits for the next gamepad key and adds it to the extra buttons. */
    private fun learnButton() {
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.mapping_extra_add)
            .setMessage(R.string.mapping_extra_press)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.setOnKeyListener { d, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            val dev = event.device
            val fromGamepad = dev != null && !dev.isVirtual &&
                ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                    (event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD)
            when {
                (keyCode == KeyEvent.KEYCODE_BACK && !fromGamepad) || keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                    d.dismiss(); true
                }
                !ExtraButtons.isAssignable(keyCode) -> {
                    Toast.makeText(this, getString(R.string.mapping_extra_standard, keyName(keyCode)), Toast.LENGTH_SHORT).show()
                    true
                }
                store.load().let { !it.extraButtons.containsKey(keyCode) && it.extraButtons.size >= ControllerMapping.MAX_EXTRA } -> {
                    Toast.makeText(this, getString(R.string.mapping_extra_full, ControllerMapping.MAX_EXTRA), Toast.LENGTH_SHORT).show()
                    d.dismiss(); true
                }
                else -> {
                    val s = store.load()
                    if (!s.extraButtons.containsKey(keyCode)) {
                        store.save(s.copy(extraButtons = s.extraButtons + (keyCode to "F2")))
                    }
                    showExtras(store.load().extraButtons)
                    Toast.makeText(this, getString(R.string.mapping_extra_added, keyName(keyCode)), Toast.LENGTH_SHORT).show()
                    d.dismiss()
                    true
                }
            }
        }
        dlg.show()
    }

    private fun saveMain() {
        if (loading) return
        val map = ControllerMapping(
            normalSpinners.map { actionNames[it.selectedItemPosition.coerceIn(actionNames.indices)] },
            modSpinners.map { actionNames[it.selectedItemPosition.coerceIn(actionNames.indices)] },
        )
        store.save(store.load().copy(controllerMapping = map))
    }

    private fun keyName(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
    }

    private fun weight(w: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    private fun spinner(labels: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@ControllerMappingActivity, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        minimumHeight = (48 * density).toInt()
        foreground = getDrawable(R.drawable.focus_frame)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        background = getDrawable(R.drawable.launcher_button)
        setTextColor(getColorStateList(R.color.launcher_button_text))
        minimumHeight = (48 * density).toInt()
        setOnClickListener { onClick() }
    }

    private fun text(s: String, size: Float, color: String) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color))
    }
}
