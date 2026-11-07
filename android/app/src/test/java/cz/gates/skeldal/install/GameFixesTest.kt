package cz.gates.skeldal.install

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameFixesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fixed = byteArrayOf(1, 2, 3, 4)

    private fun fixes(game: File, backup: File) =
        GameFixes(game, backup) { p -> if (p == "maps/BILA_VEZ.MAP") fixed.inputStream() else null }

    @Test fun replacesTheMapIgnoringCaseAndKeepsTheOriginal() {
        val game = tmp.newFolder("game")
        val map = File(game, "Maps/bila_vez.map").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(9, 9)) }
        val backup = File(tmp.root, "backup")
        assertEquals(listOf("maps/BILA_VEZ.MAP"), fixes(game, backup).apply())
        assertArrayEquals(fixed, map.readBytes())
        assertArrayEquals(byteArrayOf(9, 9), File(backup, "bila_vez.map").readBytes())
        assertFalse(File(map.parentFile, "bila_vez.map.tmp").exists())
        // second run: nothing to do
        assertTrue(fixes(game, backup).apply().isEmpty())
    }

    @Test fun doesNothingWithoutTheMap() {
        val game = tmp.newFolder("game")
        File(game, "maps").mkdirs()
        assertTrue(fixes(game, File(tmp.root, "backup")).apply().isEmpty())
        assertFalse(File(game, "maps/BILA_VEZ.MAP").exists())
    }
}
