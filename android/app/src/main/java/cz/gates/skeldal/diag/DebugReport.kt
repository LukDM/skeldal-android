package cz.gates.skeldal.diag

import android.content.Context
import android.os.Build
import cz.gates.skeldal.install.InstallationRepository
import cz.gates.skeldal.settings.SettingsStore
import java.io.OutputStream
import java.util.Date

/**
 * Text report for bug reports ("Export debug logu"): versions, device, installation,
 * settings and logs. Contains no game data and no personal files.
 */
object DebugReport {

    fun write(context: Context, out: OutputStream) {
        val w = out.bufferedWriter()
        val monitor = CrashMonitor(context.filesDir)
        val pkg = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
        w.appendLine("Brány Skeldalu - Android debug report")
        w.appendLine("created: ${Date()}")
        w.appendLine("app: ${pkg?.versionName} (${pkg?.longVersionCode})")
        w.appendLine("engine: ${monitor.engineVersion()}")
        w.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        w.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        val m = context.resources.displayMetrics
        w.appendLine("display: ${m.widthPixels}x${m.heightPixels} @ ${m.densityDpi} dpi")
        w.appendLine()
        w.appendLine("== installation ==")
        w.appendLine(InstallationRepository(context.filesDir).load()?.toString() ?: "none")
        w.appendLine()
        w.appendLine("== settings ==")
        w.appendLine(SettingsStore(context).load().toString())
        w.appendLine()
        section(w, "game.log (last run)", monitor.logFile)
        section(w, "game.prev.log (run before)", monitor.previousLogFile)
        w.appendLine("== logcat (this app) ==")
        w.appendLine(logcat())
        w.flush()
    }

    private fun section(w: java.io.Writer, title: String, f: java.io.File) {
        w.appendLine("== $title ==")
        w.appendLine(if (f.isFile) f.readText() else "(none)")
        w.appendLine()
    }

    /** Apps may read their own log lines (incl. native crash messages of the game process). */
    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-t", "3000", "-v", "time").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    } catch (e: Exception) {
        "(logcat not available: ${e.message})"
    }
}
