package cz.gates.skeldal.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashMonitorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun normalEndIsOk() {
        val m = CrashMonitor(tmp.root)
        m.markStarted()
        File(tmp.root, "game_session").delete()          // native side on normal end
        assertEquals(CrashMonitor.LastRun.Ok, m.consume(gameProcessAlive = false))
    }

    @Test
    fun markerLeftBehindIsCrashAndReportedOnce() {
        val m = CrashMonitor(tmp.root)
        m.markStarted()
        assertEquals(CrashMonitor.LastRun.Crashed, m.consume(false))
        assertEquals(CrashMonitor.LastRun.Ok, m.consume(false))
    }

    @Test
    fun runningGameIsNotACrash() {
        val m = CrashMonitor(tmp.root)
        m.markStarted()
        assertEquals(CrashMonitor.LastRun.Ok, m.consume(gameProcessAlive = true))
        assertEquals(CrashMonitor.LastRun.Crashed, m.consume(false))   // still detected later
    }

    @Test
    fun engineErrorIsReported() {
        val m = CrashMonitor(tmp.root)
        m.markStarted()
        File(tmp.root, "last_error.txt").writeText("Can't open resource file (main): SKELDAL.DDL")
        File(tmp.root, "game_session").delete()
        val r = m.consume(false)
        assertTrue(r is CrashMonitor.LastRun.Error)
        val f = CrashMonitor.friendlyMessage((r as CrashMonitor.LastRun.Error).message)
        assertTrue(f.text.contains("herní soubory"))
        assertTrue(f.offerCheck)
    }

    @Test
    fun newStartClearsOldError() {
        val m = CrashMonitor(tmp.root)
        File(tmp.root, "last_error.txt").writeText("old")
        m.markStarted()
        File(tmp.root, "game_session").delete()
        assertEquals(CrashMonitor.LastRun.Ok, m.consume(false))
    }

    @Test
    fun logTail() {
        val m = CrashMonitor(tmp.root)
        File(tmp.root, "logs").mkdirs()
        m.logFile.writeText((1..500).joinToString("\n") { "line $it" })
        val t = m.logTail(3)
        assertEquals("line 498\nline 499\nline 500", t)
    }
}
