package cz.gates.skeldal.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun importer(files: File, free: Long = Long.MAX_VALUE) =
        GameImporter(InstallationRepository(files), files) { free }

    @Test
    fun importsDirectoryAndSkipsExecutables() {
        val src = tmp.newFolder("src")
        TestData.writeDir(File(src, "Brány Skeldalu"), TestData.steamLayout)
        val files = tmp.newFolder("files")

        val inst = importer(files).import(DirectorySource(src), "Brány Skeldalu") {}

        val game = File(files, "game")
        assertTrue(File(game, "SKELDAL.DDL").isFile)
        assertEquals(500, File(game, "maps/LESPRED.MAP").length())
        assertFalse(File(game, "skeldal.exe").exists())
        assertFalse(File(game, "steam_api64.dll").exists())
        assertFalse(File(game, "temp").exists())
        assertEquals(GameInstallationDetector.SourceType.STEAM, inst.type)
        assertEquals(inst, InstallationRepository(files).load())
    }

    @Test
    fun importsZip() {
        val zip = tmp.newFile("game.zip").apply { writeBytes(TestData.zip(TestData.steamLayout, "Skeldal/")) }
        val files = tmp.newFolder("files")
        importer(files).import(ZipStreamSource { zip.inputStream() }, "game.zip") {}
        assertTrue(File(files, "game/SKELDAL.DDL").isFile)
        assertEquals(800, File(files, "game/video/INTRO.MGF").length())
    }

    @Test
    fun corruptedZipGivesReadableError() {
        val zip = tmp.newFile("bad.zip").apply { writeBytes(ByteArray(1000) { 7 }) }
        try {
            importer(tmp.newFolder("files")).import(ZipStreamSource { zip.inputStream() }, "bad.zip") {}
            fail()
        } catch (e: ImportException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun truncatedZipGivesReadableError() {
        val full = TestData.zip(TestData.steamLayout)
        val zip = tmp.newFile("cut.zip").apply { writeBytes(full.copyOf(full.size / 2)) }
        try {
            importer(tmp.newFolder("files")).import(ZipStreamSource { zip.inputStream() }, "cut.zip") {}
            fail()
        } catch (e: ImportException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun zipSlipIsRejected() {
        val zip = tmp.newFile("slip.zip").apply {
            writeBytes(TestData.zip(mapOf("SKELDAL.DDL" to 10, "../../evil.so" to 10)))
        }
        val files = tmp.newFolder("files")
        try {
            importer(files).import(ZipStreamSource { zip.inputStream() }, "slip.zip") {}
            fail()
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("nebezpečnou"))
        }
        assertFalse(File(files, "game").exists())
        assertFalse(File(tmp.root, "evil.so").exists())
    }

    @Test
    fun invalidSourceKeepsPreviousInstallation() {
        val files = tmp.newFolder("files")
        val good = tmp.newFolder("good").also { TestData.writeDir(it, TestData.steamLayout) }
        importer(files).import(DirectorySource(good), "good") {}

        val bad = tmp.newFolder("bad").also { File(it, "readme.txt").writeText("x") }
        try {
            importer(files).import(DirectorySource(bad), "bad") {}
            fail()
        } catch (e: ImportException) { }
        assertTrue(File(files, "game/SKELDAL.DDL").isFile)
        assertEquals("good", InstallationRepository(files).load()!!.sourceName)
    }

    @Test
    fun notEnoughSpace() {
        val src = tmp.newFolder("src").also { TestData.writeDir(it, TestData.steamLayout) }
        try {
            importer(tmp.newFolder("files"), free = 1024).import(DirectorySource(src), "src") {}
            fail()
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("místa"))
        }
    }

    @Test
    fun reimportReplacesDataButKeepsSaves() {
        val files = tmp.newFolder("files")
        val saves = File(files, "saves").apply { mkdirs(); File(this, "slot1.sav").writeText("save") }
        val src = tmp.newFolder("src").also { TestData.writeDir(it, TestData.steamLayout) }
        importer(files).import(DirectorySource(src), "a") {}
        File(files, "game/extra.txt").writeText("old")
        importer(files).import(DirectorySource(src), "b") {}
        assertFalse(File(files, "game/extra.txt").exists())
        assertEquals("save", File(saves, "slot1.sav").readText())
    }

    @Test
    fun verifyCreatesMetadataForOldImports() {
        val files = tmp.newFolder("files")
        TestData.writeDir(File(files, "game"), TestData.steamLayout - "skeldal.exe" - "SDL2.dll" - "steam_api64.dll")
        val repo = InstallationRepository(files)
        assertEquals(null, repo.load())
        importer(files).verifyInstalled()
        val inst = repo.load()!!
        assertEquals(GameInstallationDetector.SourceType.PC_NEW, inst.type)
        assertTrue(inst.fileCount > 0 && inst.totalBytes > 0)
    }

    @Test
    fun verifyInstalledDetectsDamage() {
        val files = tmp.newFolder("files")
        val src = tmp.newFolder("src").also { TestData.writeDir(it, TestData.steamLayout) }
        val imp = importer(files)
        imp.import(DirectorySource(src), "src") {}
        assertEquals(GameInstallationDetector.Status.VALID, imp.verifyInstalled()!!.status)
        File(files, "game/maps/LESPRED.MAP").delete()
        assertEquals(GameInstallationDetector.Status.INCOMPLETE, imp.verifyInstalled()!!.status)
        assertEquals(GameInstallationDetector.Status.INCOMPLETE, InstallationRepository(files).load()!!.status)
    }
}
