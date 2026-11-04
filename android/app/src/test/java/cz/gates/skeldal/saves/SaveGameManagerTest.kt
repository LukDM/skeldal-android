package cz.gates.skeldal.saves

import cz.gates.skeldal.install.ImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveGameManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun device(name: String): Pair<SaveGameManager, File> {
        val files = tmp.newFolder(name)
        val saves = File(files, "saves").apply { mkdirs() }
        return SaveGameManager(saves, File(files, "saves_backups")) to saves
    }

    private fun save(dir: File, name: String, content: String, time: Long) =
        File(dir, name).apply { writeText(content); setLastModified(time) }

    private fun export(m: SaveGameManager): ByteArray = ByteArrayOutputStream().also { m.export(it, "test") }.toByteArray()

    @Test
    fun recognizesEngineSaveNames() {
        assertTrue(SaveGameManager.isSaveName("sav.6543a1b2.00001234"))
        assertTrue(SaveGameManager.isSaveName("sav.6543a1b2.00001234.auto"))
        assertTrue(SaveGameManager.isSaveName("slot01.SAV"))
        assertFalse(SaveGameManager.isSaveName("skeldal.ini"))
        assertFalse(SaveGameManager.isSaveName("../sav.x"))
    }

    @Test
    fun exportAndImportToAnotherDeviceKeepsContentAndTime() {
        val (a, aSaves) = device("a")
        save(aSaves, "sav.00000001.00000010", "game1", 1_600_000_000_000)
        save(aSaves, "slot02.SAV", "old", 1_500_000_000_000)
        File(aSaves, "notes.txt").writeText("not a save")
        val zip = export(a)

        val (b, bSaves) = device("b")
        save(bSaves, "sav.00000002.00000099", "game2", 1_700_000_000_000)
        val r = b.import({ zip.inputStream() }, "test")

        assertEquals(2, r.imported)
        assertEquals("game1", File(bSaves, "sav.00000001.00000010").readText())
        assertEquals("game2", File(bSaves, "sav.00000002.00000099").readText())   // merged, not replaced
        assertFalse(File(bSaves, "notes.txt").exists())
        assertEquals(1_600_000_000_000, File(bSaves, "sav.00000001.00000010").lastModified() / 1000 * 1000)
        assertNotNull(r.backup)
    }

    @Test
    fun exportWithoutSavesFails() {
        val (a, _) = device("a")
        try { export(a); fail() } catch (e: ImportException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test
    fun corruptedOrForeignZipChangesNothing() {
        val (b, bSaves) = device("b")
        save(bSaves, "sav.1.1", "keep", 1000)
        for (bytes in listOf(ByteArray(500) { 3 }, zipOf("photo.jpg" to "x"))) {
            try { b.import({ bytes.inputStream() }, "t"); fail() } catch (e: ImportException) { }
        }
        assertEquals("keep", File(bSaves, "sav.1.1").readText())
        assertEquals(0, b.backups().size)
    }

    @Test
    fun pathTraversalEntriesAreIgnored() {
        val (b, bSaves) = device("b")
        val zip = zipOf("../../sav.evil" to "x", "sav.ok.1" to "ok")
        val r = b.import({ zip.inputStream() }, "t")
        assertEquals(1, r.imported)
        assertTrue(File(bSaves, "sav.ok.1").isFile)
        assertFalse(File(tmp.root, "sav.evil").exists())
    }

    @Test
    fun restoreReturnsToExactPreviousState() {
        val (b, bSaves) = device("b")
        save(bSaves, "sav.1.1", "before", 1000)
        b.import({ zipOf("sav.2.2" to "imported").inputStream() }, "t")
        assertTrue(File(bSaves, "sav.2.2").isFile)

        val backup = b.backups().first()
        b.restore(backup, "t")
        assertEquals("before", File(bSaves, "sav.1.1").readText())
        assertFalse(File(bSaves, "sav.2.2").exists())
    }

    @Test
    fun onlyLastBackupsAreKept() {
        val (b, bSaves) = device("b")
        save(bSaves, "sav.1.1", "x", 1000)
        repeat(SaveGameManager.MAX_BACKUPS + 3) { i ->
            b.import({ zipOf("sav.n.$i" to "$i").inputStream() }, "t")
        }
        assertEquals(SaveGameManager.MAX_BACKUPS, b.backups().size)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }
}
