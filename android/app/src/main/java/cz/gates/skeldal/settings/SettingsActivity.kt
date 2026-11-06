package cz.gates.skeldal.settings

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import cz.gates.skeldal.R

/** Display and touch settings. Applied the next time the game starts. */
class SettingsActivity : Activity() {

    companion object {
        private const val REQ_EXPORT_LOG = 10
    }

    private lateinit var store: SettingsStore
    private lateinit var scaling: Spinner
    private lateinit var crt: Spinner
    private lateinit var touchMode: Spinner
    private lateinit var fullscreen: Switch
    private lateinit var smooth: Switch
    private lateinit var vsync: Switch
    private lateinit var videoLines: Switch
    private lateinit var portrait: Switch
    private lateinit var minimap: Switch
    private lateinit var enemyLives: Spinner
    private lateinit var overlayDim: Switch
    private lateinit var gameSpeed: Spinner
    private val enemyLivesValues = GameSettings.EnemyLives.values()
    private val gameSpeedValues = GameSettings.GameSpeed.values()
    private lateinit var size: SeekBar
    private lateinit var picture: SeekBar
    private lateinit var pictureLabel: TextView
    private lateinit var opacity: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var leftHanded: Switch
    private lateinit var centered: Switch
    private lateinit var dpad: Switch
    private lateinit var bMenu: Switch
    private lateinit var bMap: Switch
    private lateinit var bAction: Switch
    private lateinit var bBattle: Switch
    private lateinit var longPress: Switch
    private lateinit var ctrlEnabled: Switch
    private lateinit var swapSticks: Switch
    private lateinit var ctrlInfo: TextView

    private val scalingValues = GameSettings.Scaling.values()
    private val crtValues = GameSettings.CrtFilter.values()
    private val touchModeValues = GameSettings.TouchMode.values()
    private val languageValues = GameSettings.Language.values()
    private lateinit var language: Spinner
    private lateinit var autosaveBg: Switch
    private val optionalSwitches = LinkedHashMap<cz.gates.skeldal.touch.GameAction, Switch>()

    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = SettingsStore(this)

        scaling = findViewById(R.id.spinner_scaling)
        crt = findViewById(R.id.spinner_crt)
        touchMode = findViewById(R.id.spinner_touch_mode)
        fullscreen = findViewById(R.id.switch_fullscreen)
        smooth = findViewById(R.id.switch_smooth)
        vsync = findViewById(R.id.switch_vsync)
        videoLines = findViewById(R.id.switch_video_scanlines)
        portrait = findViewById(R.id.switch_allow_portrait)
        minimap = findViewById(R.id.switch_always_minimap)
        enemyLives = findViewById(R.id.spinner_enemy_lives)
        overlayDim = findViewById(R.id.switch_overlay_dim)
        gameSpeed = findViewById(R.id.spinner_game_speed)
        size = findViewById(R.id.seek_touch_size)
        picture = findViewById(R.id.seek_picture_scale)
        pictureLabel = findViewById(R.id.label_picture_scale)
        opacity = findViewById(R.id.seek_touch_opacity)
        sizeLabel = findViewById(R.id.label_touch_size)
        opacityLabel = findViewById(R.id.label_touch_opacity)
        leftHanded = findViewById(R.id.switch_left_handed)
        centered = findViewById(R.id.switch_centered)
        dpad = findViewById(R.id.switch_dpad)
        bMenu = findViewById(R.id.switch_btn_menu)
        bMap = findViewById(R.id.switch_btn_map)
        bAction = findViewById(R.id.switch_btn_action)
        bBattle = findViewById(R.id.switch_btn_battle)
        longPress = findViewById(R.id.switch_longpress)
        ctrlEnabled = findViewById(R.id.switch_controller)
        swapSticks = findViewById(R.id.switch_swap_sticks)
        ctrlInfo = findViewById(R.id.label_controller_info)
        findViewById<Button>(R.id.btn_touch_layout).setOnClickListener {
            startActivity(android.content.Intent(this, cz.gates.skeldal.touch.TouchLayoutEditorActivity::class.java))
        }
        findViewById<Button>(R.id.btn_controller_mapping).setOnClickListener {
            startActivity(android.content.Intent(this, ControllerMappingActivity::class.java))
        }

        scaling.adapter = adapter(R.array.scaling_labels)
        crt.adapter = adapter(R.array.crt_labels)
        enemyLives.adapter = adapter(R.array.enemy_lives_labels)
        gameSpeed.adapter = adapter(R.array.game_speed_labels)
        touchMode.adapter = adapter(R.array.touch_mode_labels)
        autosaveBg = findViewById(R.id.switch_autosave_bg)
        val optBox = findViewById<android.widget.LinearLayout>(R.id.optional_buttons)
        val labels = mapOf(
            cz.gates.skeldal.touch.GameAction.SAVE to "ULOŽIT (F2 – uložit hru)",
            cz.gates.skeldal.touch.GameAction.LOAD to "NAHRÁT (F3 – nahrát hru)",
            cz.gates.skeldal.touch.GameAction.INVENTORY to "INVENT. (I – inventář)",
            cz.gates.skeldal.touch.GameAction.BOOK to "KNIHA (F6)",
            cz.gates.skeldal.touch.GameAction.SPELLS to "KOUZLA (F7)",
            cz.gates.skeldal.touch.GameAction.REST to "SPÁT (F4 – odpočinek)",
            cz.gates.skeldal.touch.GameAction.SETTINGS to "NASTAV. (F1 – nastavení hry)",
            cz.gates.skeldal.touch.GameAction.KEYBOARD to "⌨ (klávesnice kdykoli – zapnout / vypnout)",
            cz.gates.skeldal.touch.GameAction.CTRL to "CTRL (zamykací – s klepnutím na portrét rozdělí skupinu)",
            cz.gates.skeldal.touch.GameAction.MERGE to "SLOUČIT (Insert – spojí postavy na jednom políčku)",
            cz.gates.skeldal.touch.GameAction.OVERLAY_MAP to "MAPA+ (O – průhledná mapa přes obraz)",
            cz.gates.skeldal.touch.GameAction.CONSOLE to "KONZOLE (` – konzole s cheaty)",
        )
        for (a in cz.gates.skeldal.touch.GameAction.OPTIONAL_BUTTONS) {
            val sw = layoutInflater.inflate(R.layout.settings_switch, optBox, false) as Switch
            sw.text = labels[a] ?: a.label
            optBox.addView(sw)
            optionalSwitches[a] = sw
        }
        language = findViewById(R.id.spinner_language)
        language.adapter = adapter(R.array.language_labels)
        val langs = cz.gates.skeldal.install.InstallationRepository(filesDir).load()?.languages ?: emptyList()
        findViewById<TextView>(R.id.label_language_hint).text =
            if (langs.any { it.equals("en", true) }) getString(R.string.settings_language_ok, (listOf("cz") + langs).joinToString(", "))
            else getString(R.string.settings_language_missing)
        findViewById<Button>(R.id.btn_export_log).setOnClickListener {
            val name = "skeldal-debug-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".txt"
            startActivityForResult(
                android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TITLE, name),
                REQ_EXPORT_LOG
            )
        }

        show(store.load())

        val select = SimpleSelect { save() }
        for (sp in listOf(scaling, crt, touchMode, language, enemyLives, gameSpeed)) sp.onItemSelectedListener = select
        for (sw in listOf(fullscreen, smooth, vsync, videoLines, portrait, minimap, overlayDim, leftHanded, centered, dpad, bMenu, bMap, bAction, bBattle, longPress,
                          ctrlEnabled, swapSticks, autosaveBg) + optionalSwitches.values) {
            sw.setOnCheckedChangeListener { _, _ -> save() }
        }
        val seek = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { updateLabels(); if (fromUser) save() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        size.setOnSeekBarChangeListener(seek)
        picture.setOnSeekBarChangeListener(seek)
        opacity.setOnSeekBarChangeListener(seek)

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            store.save(GameSettings())
            show(GameSettings())
        }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val names = Gamepads.connectedNames()
        ctrlInfo.text = if (names.isEmpty()) getString(R.string.settings_controller_none)
                        else getString(R.string.settings_controller_found, names.joinToString(", "))
    }

    private fun adapter(arrayRes: Int) =
        ArrayAdapter.createFromResource(this, arrayRes, android.R.layout.simple_spinner_item).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // seek bars: size 50..200 %, opacity 10..100 %
    private fun sizeValue() = size.progress + 50
    private fun opacityValue() = opacity.progress + 10
    private fun pictureValue() = picture.progress + 50

    private fun updateLabels() {
        sizeLabel.text = getString(R.string.settings_touch_size, sizeValue())
        opacityLabel.text = getString(R.string.settings_touch_opacity, opacityValue())
        pictureLabel.text = getString(R.string.settings_picture_scale, pictureValue())
    }

    private fun show(s: GameSettings) {
        loading = true
        scaling.setSelection(scalingValues.indexOf(s.scaling))
        crt.setSelection(crtValues.indexOf(s.crtFilter))
        touchMode.setSelection(touchModeValues.indexOf(s.touchMode))
        language.setSelection(languageValues.indexOf(s.language))
        autosaveBg.isChecked = s.autosaveOnBackground
        for ((a, sw) in optionalSwitches) sw.isChecked = a in s.touchExtraButtons
        fullscreen.isChecked = s.fullscreen
        smooth.isChecked = s.smoothScaling
        vsync.isChecked = s.vsync
        videoLines.isChecked = s.videoScanlines
        portrait.isChecked = s.allowPortrait
        minimap.isChecked = s.alwaysMinimap
        enemyLives.setSelection(enemyLivesValues.indexOf(s.enemyLives))
        overlayDim.isChecked = s.overlayMapDim
        gameSpeed.setSelection(gameSpeedValues.indexOf(s.gameSpeed))
        size.progress = s.touchSize - 50
        picture.progress = s.pictureScale - 50
        opacity.progress = s.touchOpacity - 10
        leftHanded.isChecked = s.touchLeftHanded
        centered.isChecked = s.touchCentered
        dpad.isChecked = s.touchDpad
        bMenu.isChecked = s.touchMenu
        bMap.isChecked = s.touchMap
        bAction.isChecked = s.touchAction
        bBattle.isChecked = s.touchBattle
        longPress.isChecked = s.longPressRightClick
        ctrlEnabled.isChecked = s.controllerEnabled
        swapSticks.isChecked = s.swapSticks
        updateLabels()
        loading = false
    }

    private fun save() {
        if (loading) return
        store.save(
            store.load().copy(
                scaling = scalingValues[scaling.selectedItemPosition.coerceIn(scalingValues.indices)],
                fullscreen = fullscreen.isChecked,
                smoothScaling = smooth.isChecked,
                crtFilter = crtValues[crt.selectedItemPosition.coerceIn(crtValues.indices)],
                vsync = vsync.isChecked,
                videoScanlines = videoLines.isChecked,
                allowPortrait = portrait.isChecked,
                alwaysMinimap = minimap.isChecked,
                enemyLives = enemyLivesValues[enemyLives.selectedItemPosition.coerceIn(enemyLivesValues.indices)],
                overlayMapDim = overlayDim.isChecked,
                gameSpeed = gameSpeedValues[gameSpeed.selectedItemPosition.coerceIn(gameSpeedValues.indices)],
                pictureScale = pictureValue(),
                touchMode = touchModeValues[touchMode.selectedItemPosition.coerceIn(touchModeValues.indices)],
                touchSize = sizeValue(),
                touchOpacity = opacityValue(),
                touchLeftHanded = leftHanded.isChecked,
                touchCentered = centered.isChecked,
                touchDpad = dpad.isChecked,
                touchMenu = bMenu.isChecked,
                touchMap = bMap.isChecked,
                touchAction = bAction.isChecked,
                touchBattle = bBattle.isChecked,
                longPressRightClick = longPress.isChecked,
                controllerEnabled = ctrlEnabled.isChecked,
                swapSticks = swapSticks.isChecked,
                language = languageValues[language.selectedItemPosition.coerceIn(languageValues.indices)],
                autosaveOnBackground = autosaveBg.isChecked,
                touchExtraButtons = optionalSwitches.filterValues { it.isChecked }.keys,
            )
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_EXPORT_LOG || resultCode != RESULT_OK) return
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val msg = try {
                contentResolver.openOutputStream(uri, "wt")?.use { cz.gates.skeldal.diag.DebugReport.write(this, it) }
                getString(R.string.settings_export_log_done)
            } catch (e: Exception) {
                getString(R.string.saves_failed) + " (" + e.javaClass.simpleName + ")"
            }
            ui.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
        }.start()
    }

    private class SimpleSelect(private val onChange: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange()
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
