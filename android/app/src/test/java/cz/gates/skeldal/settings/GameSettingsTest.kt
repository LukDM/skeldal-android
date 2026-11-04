package cz.gates.skeldal.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun defaultsKeepAspectRatio() {
        val d = GameSettings.fromMap(emptyMap<String, Any>())
        assertEquals(GameSettings.Scaling.ASPECT, d.scaling)
        assertTrue(d.fullscreen)
    }

    @Test
    fun roundTrip() {
        val s = GameSettings(GameSettings.Scaling.INTEGER, false, true, GameSettings.CrtFilter.SCANLINES, true)
        assertEquals(s, GameSettings.fromMap(s.toMap()))
        assertEquals(GameSettings.VERSION, s.toMap()[GameSettings.KEY_VERSION])
    }

    @Test
    fun corruptedValuesFallBackToDefaults() {
        val s = GameSettings.fromMap(mapOf("settingsVersion" to 1, "scaling" to "ZOOM", "fullscreen" to "yes", "crtFilter" to 5))
        assertEquals(GameSettings(), s)
    }

    @Test
    fun iniContainsEngineKeys() {
        val ini = GameConfigWriter.render(
            GameSettings(scaling = GameSettings.Scaling.STRETCH, smoothScaling = true),
            File("/data/game dir/game"), File("/data/saves")
        )
        assertTrue(ini.contains("[paths]\nroot=/data/game dir/game\nsavegame=/data/saves\n"))
        assertTrue(ini.contains("scaling=stretch\n"))
        assertTrue(ini.contains("scale_quality=linear\n"))
        assertTrue(ini.contains("fullscreen=on\n"))
        assertTrue(ini.contains("crt_filter=none\n"))
        assertTrue(ini.contains("vsync=off\n"))
    }

    @Test
    fun writeIsAtomicAndCreatesSaves() {
        val target = File(tmp.root, "skeldal.ini")
        val saves = File(tmp.root, "saves")
        GameConfigWriter.write(target, GameSettings(), File(tmp.root, "game"), saves)
        GameConfigWriter.write(target, GameSettings(scaling = GameSettings.Scaling.INTEGER), File(tmp.root, "game"), saves)
        assertTrue(saves.isDirectory)
        assertTrue(target.readText().contains("scaling=integer"))
        assertTrue(!File(target.path + ".tmp").exists())
    }

    @Test
    fun pictureScaleIsClampedAndWritten() {
        assertEquals(50, GameSettings.fromMap(mapOf("pictureScale" to 10)).pictureScale)
        assertEquals(100, GameSettings.fromMap(mapOf("pictureScale" to 300)).pictureScale)
        val s = GameSettings(pictureScale = 85)
        assertEquals(s, GameSettings.fromMap(s.toMap()))
        val ini = GameConfigWriter.render(s, File("/g"), File("/s"))
        assertTrue(ini.contains("picture_scale=85\n"))
    }
}
