package cz.gates.skeldal.diag

import java.io.File

/**
 * Detects how the last game session ended, from files shared with the native side
 * (platform/android/app_start.cpp):
 *  - game_session    created by the launcher before start, removed by the game on a normal end
 *  - last_error.txt  last error reported by the engine
 * A crash never marks the imported game data invalid.
 */
class CrashMonitor(private val filesDir: File) {

    sealed class LastRun {
        object Ok : LastRun()
        /** The game process died without finishing (native crash, killed by the system). */
        object Crashed : LastRun()
        /** The game ended with an error message. */
        data class Error(val message: String) : LastRun()
    }

    private val marker get() = File(filesDir, "game_session")
    private val lastError get() = File(filesDir, "last_error.txt")
    val logFile: File get() = File(filesDir, "logs/game.log")
    val previousLogFile: File get() = File(filesDir, "logs/game.prev.log")
    val engineVersionFile: File get() = File(filesDir, "engine_version.txt")

    /** Call right before starting GameActivity. */
    fun markStarted() {
        lastError.delete()
        marker.writeText(System.currentTimeMillis().toString())
    }

    /**
     * Result of the last session; [gameProcessAlive] must tell whether the game process still
     * runs (then the session is not over yet). The state is consumed (reported once).
     */
    fun consume(gameProcessAlive: Boolean): LastRun {
        if (gameProcessAlive) return LastRun.Ok
        val err = if (lastError.isFile) lastError.readText().trim() else ""
        val crashed = marker.exists()
        marker.delete()
        lastError.delete()
        return when {
            err.isNotEmpty() -> LastRun.Error(err)
            crashed -> LastRun.Crashed
            else -> LastRun.Ok
        }
    }

    fun engineVersion(): String =
        if (engineVersionFile.isFile) engineVersionFile.readText().trim().replace('\n', ' ') else "?"

    fun logTail(lines: Int): String {
        val f = logFile.takeIf { it.isFile } ?: return ""
        return f.readLines().takeLast(lines).joinToString("\n")
    }

    companion object {
        /** Turns an engine error into a sentence for a normal user (technical text stays in the log). */
        fun friendlyMessage(raw: String): FriendlyError = when {
            raw.contains("Game data not found", true) || raw.contains("Can't open resource file", true) ||
                raw.contains("Failed to open file", true) || raw.contains("not been found", true) ->
                FriendlyError("Hru se nepodařilo spustit, protože nebyly nalezeny potřebné herní soubory.", offerCheck = true)
            raw.contains("skeldal.ini", true) || raw.contains("configuration", true) ->
                FriendlyError("Hru se nepodařilo spustit kvůli chybě v konfiguraci. Zkuste v Nastavení obnovit výchozí hodnoty.", offerCheck = false)
            raw.contains("Memory", true) ->
                FriendlyError("Hra skončila, protože zařízení nemá dost paměti. Zavřete jiné aplikace a zkuste to znovu.", offerCheck = false)
            else -> FriendlyError("Hra skončila s chybou.", offerCheck = true)
        }
    }

    data class FriendlyError(val text: String, val offerCheck: Boolean)
}
