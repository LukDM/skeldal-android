package cz.gates.skeldal.settings

/**
 * Launcher settings. Stored as a flat key/value map (SharedPreferences on Android) with a
 * [VERSION] field so that later format changes can be migrated without losing the user's choice.
 */
data class GameSettings(
    val scaling: Scaling = Scaling.ASPECT,
    val fullscreen: Boolean = true,
    val smoothScaling: Boolean = false,
    val crtFilter: CrtFilter = CrtFilter.OFF,
    val vsync: Boolean = false,
    /** Videos with darker every second line like the original game. */
    val videoScanlines: Boolean = false,
    /** Allow portrait (picture at the top, controls below); otherwise landscape only. */
    val allowPortrait: Boolean = true,
    /** Size of the game picture in % of the largest size that fits (50..100). */
    val pictureScale: Int = 100,
    // touch controls (Milestone 4)
    val touchMode: TouchMode = TouchMode.AUTO,
    val touchSize: Int = 100,
    val touchOpacity: Int = 60,
    val touchLeftHanded: Boolean = false,
    val touchCentered: Boolean = false,
    val touchDpad: Boolean = true,
    val touchMenu: Boolean = true,
    val touchMap: Boolean = true,
    val touchAction: Boolean = true,
    val touchBattle: Boolean = true,
    val longPressRightClick: Boolean = true,
    /** Custom pad position from the layout editor (fraction of the screen), negative = default. */
    val touchPadX: Float = -1f,
    val touchPadY: Float = -1f,
    /** Custom positions of single buttons from the layout editor (fractions of the screen). */
    val touchButtonPositions: Map<cz.gates.skeldal.touch.GameAction, Pair<Float, Float>> = emptyMap(),
    /** Size of the movement pad in % of [touchSize] (layout editor). */
    val touchPadSize: Int = 100,
    /** Sizes of single buttons in % of [touchSize] (layout editor); missing = 100. */
    val touchButtonSizes: Map<cz.gates.skeldal.touch.GameAction, Int> = emptyMap(),
    /** Optional on-screen buttons the user added (save, load, ...). */
    val touchExtraButtons: Set<cz.gates.skeldal.touch.GameAction> = emptySet(),
    /** Save the game automatically (like F5) when the app goes to the background. */
    val autosaveOnBackground: Boolean = true,
    // gamepad (Milestone 5)
    val controllerEnabled: Boolean = true,
    val swapSticks: Boolean = false,
    val controllerMapping: ControllerMapping = ControllerMapping.defaults(),
    val slowCursorWithMod: Boolean = true,
    /** Android key code -> action for buttons outside the standard layout (M1, M2, ...). */
    val extraButtons: Map<Int, String> = emptyMap(),
    /** Game language; anything but CZ needs lang/<folder> in the imported data. */
    val language: Language = Language.CZ,
) {
    enum class Language(val folder: String?) { CZ(null), EN("en") }

    val padCenter: Pair<Float, Float>? get() = if (touchPadX >= 0 && touchPadY >= 0) touchPadX to touchPadY else null

    fun touchOptions(): cz.gates.skeldal.touch.TouchLayout.Options {
        val buttons = buildList {
            if (touchMenu) add(cz.gates.skeldal.touch.GameAction.MENU)
            if (touchMap) add(cz.gates.skeldal.touch.GameAction.MAP)
            if (touchAction) add(cz.gates.skeldal.touch.GameAction.ACTION)
            if (touchBattle) add(cz.gates.skeldal.touch.GameAction.BATTLE)
            addAll(cz.gates.skeldal.touch.GameAction.OPTIONAL_BUTTONS.filter { it in touchExtraButtons })
        }
        return cz.gates.skeldal.touch.TouchLayout.Options(
            sizePercent = touchSize, leftHanded = touchLeftHanded, centered = touchCentered,
            showDpad = touchDpad, buttons = buttons, padCenter = padCenter,
            buttonCenters = touchButtonPositions.filterKeys { it in buttons },
            padSizePercent = touchPadSize, buttonSizes = touchButtonSizes,
        )
    }

    /** ALWAYS = always shown, AUTO = hidden while a gamepad/keyboard is used, NEVER = never shown. */
    enum class TouchMode { ALWAYS, AUTO, NEVER }

    enum class Scaling(val ini: String) { ASPECT("aspect"), INTEGER("integer"), STRETCH("stretch") }

    /** Values understood by the engine (crt_filter in skeldal.ini). */
    enum class CrtFilter(val ini: String) {
        OFF("none"), AUTO("auto"), SCANLINES("scanlines"), SCANLINES_2("scanlines_2"),
        RGB_MATRIX_2("rgbmatrix_2"), RGB_MATRIX_3("rgbmatrix_3")
    }

    fun toMap(): Map<String, Any> = mapOf(
        KEY_VERSION to VERSION,
        "scaling" to scaling.name,
        "fullscreen" to fullscreen,
        "smoothScaling" to smoothScaling,
        "crtFilter" to crtFilter.name,
        "vsync" to vsync,
        "videoScanlines" to videoScanlines,
        "allowPortrait" to allowPortrait,
        "pictureScale" to pictureScale,
        "touchMode" to touchMode.name,
        "touchSize" to touchSize,
        "touchOpacity" to touchOpacity,
        "touchLeftHanded" to touchLeftHanded,
        "touchCentered" to touchCentered,
        "touchDpad" to touchDpad,
        "touchMenu" to touchMenu,
        "touchMap" to touchMap,
        "touchAction" to touchAction,
        "touchBattle" to touchBattle,
        "longPressRightClick" to longPressRightClick,
        "touchPadX" to touchPadX,
        "touchPadY" to touchPadY,
        "touchButtonPositions" to touchButtonPositions.entries.joinToString(";") { "${it.key.name}:${it.value.first},${it.value.second}" },
        "touchPadSize" to touchPadSize,
        "touchButtonSizes" to touchButtonSizes.entries.joinToString(";") { "${it.key.name}:${it.value}" },
        "touchExtraButtons" to touchExtraButtons.joinToString(",") { it.name },
        "autosaveOnBackground" to autosaveOnBackground,
        "controllerEnabled" to controllerEnabled,
        "swapSticks" to swapSticks,
        "slowCursorWithMod" to slowCursorWithMod,
        "extraButtons" to ExtraButtons.encode(extraButtons),
        "language" to language.name,
    ) + (0 until ControllerMapping.BUTTON_COUNT).flatMap { i ->
        listOf("ctrl_n_$i" to controllerMapping.normal[i], "ctrl_m_$i" to controllerMapping.withMod[i])
    }

    companion object {
        const val VERSION = 1
        const val KEY_VERSION = "settingsVersion"

        /** Reads settings; unknown or broken values fall back to defaults, older versions are migrated. */
        fun fromMap(m: Map<String, *>): GameSettings {
            val d = GameSettings()
            val version = (m[KEY_VERSION] as? Int) ?: 0
            // version 0 = nothing stored yet -> defaults; future migrations go here (if version < N ...)
            if (version == 0 && m.isEmpty()) return d
            return GameSettings(
                scaling = enumOr(m["scaling"], d.scaling),
                fullscreen = (m["fullscreen"] as? Boolean) ?: d.fullscreen,
                smoothScaling = (m["smoothScaling"] as? Boolean) ?: d.smoothScaling,
                crtFilter = enumOr(m["crtFilter"], d.crtFilter),
                vsync = (m["vsync"] as? Boolean) ?: d.vsync,
                videoScanlines = (m["videoScanlines"] as? Boolean) ?: d.videoScanlines,
                allowPortrait = (m["allowPortrait"] as? Boolean) ?: d.allowPortrait,
                pictureScale = ((m["pictureScale"] as? Int) ?: d.pictureScale).coerceIn(50, 100),
                touchMode = enumOr(m["touchMode"], d.touchMode),
                touchSize = ((m["touchSize"] as? Int) ?: d.touchSize).coerceIn(50, 200),
                touchOpacity = ((m["touchOpacity"] as? Int) ?: d.touchOpacity).coerceIn(10, 100),
                touchLeftHanded = (m["touchLeftHanded"] as? Boolean) ?: d.touchLeftHanded,
                touchCentered = (m["touchCentered"] as? Boolean) ?: d.touchCentered,
                touchDpad = (m["touchDpad"] as? Boolean) ?: d.touchDpad,
                touchMenu = (m["touchMenu"] as? Boolean) ?: d.touchMenu,
                touchMap = (m["touchMap"] as? Boolean) ?: d.touchMap,
                touchAction = (m["touchAction"] as? Boolean) ?: d.touchAction,
                touchBattle = (m["touchBattle"] as? Boolean) ?: d.touchBattle,
                longPressRightClick = (m["longPressRightClick"] as? Boolean) ?: d.longPressRightClick,
                touchPadX = frac(m["touchPadX"]),
                touchPadY = frac(m["touchPadY"]),
                touchButtonPositions = readPositions(m["touchButtonPositions"] as? String),
                touchPadSize = ((m["touchPadSize"] as? Int) ?: d.touchPadSize).coerceIn(
                    cz.gates.skeldal.touch.TouchLayout.MIN_ITEM_SIZE, cz.gates.skeldal.touch.TouchLayout.MAX_ITEM_SIZE),
                touchButtonSizes = readSizes(m["touchButtonSizes"] as? String),
                touchExtraButtons = (m["touchExtraButtons"] as? String).orEmpty().split(',')
                    .mapNotNull { n -> cz.gates.skeldal.touch.GameAction.OPTIONAL_BUTTONS.firstOrNull { it.name == n } }.toSet(),
                autosaveOnBackground = (m["autosaveOnBackground"] as? Boolean) ?: d.autosaveOnBackground,
                controllerEnabled = (m["controllerEnabled"] as? Boolean) ?: d.controllerEnabled,
                swapSticks = (m["swapSticks"] as? Boolean) ?: d.swapSticks,
                controllerMapping = readMapping(m, d.controllerMapping),
                slowCursorWithMod = (m["slowCursorWithMod"] as? Boolean) ?: d.slowCursorWithMod,
                extraButtons = ExtraButtons.decode(m["extraButtons"] as? String),
                language = enumOr(m["language"], d.language),
            )
        }

        private fun readPositions(s: String?): Map<cz.gates.skeldal.touch.GameAction, Pair<Float, Float>> {
            if (s.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<cz.gates.skeldal.touch.GameAction, Pair<Float, Float>>()
            for (part in s.split(';')) {
                val a = cz.gates.skeldal.touch.GameAction.values().firstOrNull { it.name == part.substringBefore(':') } ?: continue
                val xy = part.substringAfter(':', "").split(',').mapNotNull { it.toFloatOrNull() }
                if (xy.size == 2 && xy.all { it in 0f..1f } && !a.repeats) out[a] = xy[0] to xy[1]
            }
            return out
        }

        private fun readSizes(s: String?): Map<cz.gates.skeldal.touch.GameAction, Int> {
            if (s.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<cz.gates.skeldal.touch.GameAction, Int>()
            for (part in s.split(';')) {
                val a = cz.gates.skeldal.touch.GameAction.values().firstOrNull { it.name == part.substringBefore(':') } ?: continue
                val v = part.substringAfter(':', "").toIntOrNull() ?: continue
                if (!a.repeats) out[a] = v.coerceIn(cz.gates.skeldal.touch.TouchLayout.MIN_ITEM_SIZE, cz.gates.skeldal.touch.TouchLayout.MAX_ITEM_SIZE)
            }
            return out
        }

        /** a saved position fraction 0..1, anything else = default (-1) */
        private fun frac(v: Any?): Float = (v as? Float)?.takeIf { it in 0f..1f } ?: -1f

        private fun readMapping(m: Map<String, *>, def: ControllerMapping): ControllerMapping {
            val n = (0 until ControllerMapping.BUTTON_COUNT).map { i ->
                (m["ctrl_n_$i"] as? String)?.takeIf { ControllerMapping.valid(it) } ?: def.normal[i]
            }
            val mod = (0 until ControllerMapping.BUTTON_COUNT).map { i ->
                (m["ctrl_m_$i"] as? String)?.takeIf { ControllerMapping.valid(it) } ?: def.withMod[i]
            }
            return ControllerMapping(n, mod)
        }

        private inline fun <reified T : Enum<T>> enumOr(v: Any?, def: T): T =
            try { if (v is String) enumValueOf<T>(v) else def } catch (e: IllegalArgumentException) { def }
    }
}
