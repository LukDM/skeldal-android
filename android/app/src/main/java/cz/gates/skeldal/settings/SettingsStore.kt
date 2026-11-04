package cz.gates.skeldal.settings

import android.content.Context

/** SharedPreferences backed storage for [GameSettings]. Survives app updates. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): GameSettings = GameSettings.fromMap(prefs.all)

    fun save(s: GameSettings) {
        val e = prefs.edit()
        for ((k, v) in s.toMap()) {
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.apply()
    }
}
