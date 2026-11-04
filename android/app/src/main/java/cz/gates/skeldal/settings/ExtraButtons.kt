package cz.gates.skeldal.settings

/**
 * Additional gamepad buttons outside the standard controller layout (e.g. M1/M2 on Retroid
 * handhelds, C/Z, BUTTON_1..16). The launcher learns their Android key code; in the game they
 * are passed to the engine as controller buttons 23..31 (NativeBridge.nativeExtraButton), so
 * every action works, including mouse clicks, the modifier and the slow cursor.
 */
object ExtraButtons {

    /** All engine actions are available. */
    val ACTIONS: List<Pair<String, String>> get() = ControllerMapping.ACTIONS

    /** Key codes of the standard layout; those are configured in the main table. */
    val STANDARD_KEYCODES: Set<Int> = setOf(
        96, 97, 99, 100,          // BUTTON_A, B, X, Y
        102, 103, 106, 107,       // L1, R1, THUMBL, THUMBR
        104, 105,                 // L2, R2 (digital trigger keys; triggers are rows 21/22)
        108, 109, 110,            // START, SELECT, MODE
        19, 20, 21, 22, 23,       // DPAD + center
        4, 82,                    // BACK, MENU
    )

    fun isAssignable(keyCode: Int) = keyCode > 0 && keyCode !in STANDARD_KEYCODES

    /** key code -> extra slot 0..8 (engine button 23 + slot); stable: ordered by key code. */
    fun slots(map: Map<Int, String>): Map<Int, Int> =
        map.keys.sorted().take(ControllerMapping.MAX_EXTRA).withIndex().associate { (i, code) -> code to i }

    fun encode(map: Map<Int, String>): String =
        map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    fun decode(s: String?): Map<Int, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        for (part in s.split(',')) {
            val k = part.substringBefore(':').trim().toIntOrNull() ?: continue
            val a = part.substringAfter(':', "").trim()
            if (isAssignable(k) && ControllerMapping.valid(a) && out.size < ControllerMapping.MAX_EXTRA) out[k] = a
        }
        return out
    }
}
