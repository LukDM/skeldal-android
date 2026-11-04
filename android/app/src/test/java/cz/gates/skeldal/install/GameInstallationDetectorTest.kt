package cz.gates.skeldal.install

import cz.gates.skeldal.install.GameInstallationDetector.SourceType
import cz.gates.skeldal.install.GameInstallationDetector.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInstallationDetectorTest {

    @Test
    fun validSteamInstallation() {
        val r = GameInstallationDetector.detect(TestData.entries(TestData.steamLayout))
        assertEquals(Status.VALID, r.status)
        assertEquals(SourceType.STEAM, r.sourceType)
        assertEquals("", r.rootPrefix)
        assertEquals(2, r.musicTracks)
        assertTrue(r.hasIntroVideo)
        assertEquals(listOf("en"), r.languages)
    }

    @Test
    fun executablesLibrariesConfigsAndTempAreNotCopied() {
        val r = GameInstallationDetector.detect(TestData.entries(TestData.steamLayout))
        val copied = r.files.map { it.path }.toSet()
        for (p in listOf("skeldal.exe", "SDL2.dll", "steam_api64.dll", "skeldal.ini", "wskeldal.ini",
                         "skeldal_en.bat", "temp/titulky.dec")) {
            assertFalse("$p must be skipped", p in copied)
            assertTrue(p in r.skipped)
        }
        assertTrue("TITULKY.ENC" in copied)
        assertTrue("SKELDAL.DDL" in copied)
    }

    @Test
    fun installationNestedInParentFolder() {
        val r = GameInstallationDetector.detect(TestData.entries(TestData.steamLayout, "Brány Skeldalu/"))
        assertEquals(Status.VALID, r.status)
        assertEquals("Brány Skeldalu/", r.rootPrefix)
        assertTrue(r.files.any { it.path == "maps/LESPRED.MAP" })
    }

    @Test
    fun shallowestDdlWins() {
        val entries = TestData.entries(TestData.steamLayout, "a/") + SourceEntry("a/backup/old/SKELDAL.DDL", 1)
        assertEquals("a/", GameInstallationDetector.detect(entries).rootPrefix)
    }

    @Test
    fun missingDdlIsInvalid() {
        val r = GameInstallationDetector.detect(TestData.entries(TestData.steamLayout - "SKELDAL.DDL"))
        assertEquals(Status.INVALID, r.status)
        assertEquals(listOf("SKELDAL.DDL"), r.missingRequired)
    }

    @Test
    fun wrongDirectoryIsInvalid() {
        val r = GameInstallationDetector.detect(listOf(SourceEntry("DCIM/photo.jpg", 10), SourceEntry("notes.txt", 3)))
        assertEquals(Status.INVALID, r.status)
    }

    @Test
    fun missingDefaultMapIsIncomplete() {
        val r = GameInstallationDetector.detect(TestData.entries(TestData.steamLayout - "maps/LESPRED.MAP"))
        assertEquals(Status.INCOMPLETE, r.status)
        assertEquals(listOf(GameInstallationDetector.DEFAULT_MAP), r.missingExpected)
    }

    @Test
    fun namesAreMatchedCaseInsensitivelyLikeTheEngine() {
        val r = GameInstallationDetector.detect(
            listOf(SourceEntry("skeldal.ddl", 10), SourceEntry("MAPS/lespred.map", 10), SourceEntry("Music/track01.mus", 5))
        )
        assertEquals(Status.VALID, r.status)
        assertEquals(1, r.musicTracks)
        // original names are kept, the engine resolves case at run time
        assertTrue(r.files.any { it.path == "MAPS/lespred.map" })
    }

    @Test
    fun sourceTypeHeuristics() {
        fun type(vararg names: String) =
            GameInstallationDetector.detect(names.map { SourceEntry(it, 1) } + SourceEntry("SKELDAL.DDL", 1)).sourceType
        assertEquals(SourceType.GOG, type("Galaxy64.dll"))
        assertEquals(SourceType.PC_SDL, type("SDL2.dll", "skeldal.exe"))
        assertEquals(SourceType.ORIGINAL, type("SKELDAL.EXE"))
        assertEquals(SourceType.UNKNOWN, type())
    }

    @Test
    fun steamDataWithoutExecutablesIsRecognizedAsNewPcVersion() {
        val r = GameInstallationDetector.detect(
            listOf(SourceEntry("SKELDAL.DDL", 1), SourceEntry("lang/en/ui.csv", 1), SourceEntry("maps/LESPRED.MAP", 1))
        )
        assertEquals(SourceType.PC_NEW, r.sourceType)
    }

    @Test
    fun pathsWithSpacesAndUtf8() {
        val r = GameInstallationDetector.detect(
            listOf(SourceEntry("Moje hry/Brány Skeldalu/SKELDAL.DDL", 1), SourceEntry("Moje hry/Brány Skeldalu/maps/LESPRED.MAP", 1))
        )
        assertEquals("Moje hry/Brány Skeldalu/", r.rootPrefix)
        assertEquals(Status.VALID, r.status)
    }
}
