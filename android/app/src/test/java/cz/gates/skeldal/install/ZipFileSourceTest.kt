package cz.gates.skeldal.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipFileSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipFile(name: String, layout: Map<String, Int>, prefix: String = "", charset: Charset = Charsets.UTF_8,
                        stored: Boolean = false): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream(), charset).use { z ->
            for ((p, s) in layout) {
                val data = ByteArray(s) { (it % 251).toByte() }
                val e = ZipEntry(prefix + p)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return f
    }

    private fun importer(files: File) = GameImporter(InstallationRepository(files), files) { Long.MAX_VALUE }

    @Test
    fun importsUtf8Zip() {
        val files = tmp.newFolder("files")
        ZipFileSource(zipFile("a.zip", TestData.steamLayout, "Brány Skeldalu/")).use {
            importer(files).import(it, "a.zip") {}
        }
        assertTrue(File(files, "game/SKELDAL.DDL").isFile)
        assertEquals(800, File(files, "game/video/INTRO.MGF").length())
    }

    @Test
    fun windowsExplorerCodePageNamesAreDecoded() {
        // Windows Explorer writes names in the OEM code page (CP852 for Czech) without the UTF-8 flag
        val zip = zipFile("explorer.zip", TestData.steamLayout, "Brány Skeldalu/", Charset.forName("IBM852"))
        val src = ZipFileSource(zip)
        val list = src.list()
        assertTrue(list.any { it.path == "Brány Skeldalu/SKELDAL.DDL" })
        val files = tmp.newFolder("files")
        importer(files).import(src, "explorer.zip") {}
        src.close()
        assertTrue(File(files, "game/maps/LESPRED.MAP").isFile)
    }

    @Test
    fun storedEntriesWork() {
        val files = tmp.newFolder("files")
        ZipFileSource(zipFile("stored.zip", TestData.steamLayout, stored = true)).use {
            importer(files).import(it, "stored.zip") {}
        }
        assertEquals(500, File(files, "game/maps/LESPRED.MAP").length())
    }

    @Test
    fun allFilesAreImportedLikeAFileManagerSeesThem() {
        val big = (1..300).associate { "maps/M$it.MAP" to 100 } + TestData.steamLayout
        val files = tmp.newFolder("files")
        val inst = ZipFileSource(zipFile("big.zip", big)).use { importer(files).import(it, "big.zip") {} }
        val expected = GameInstallationDetector.detect(TestData.entries(big)).files.size
        assertEquals(expected, inst.fileCount)
        assertEquals(expected, File(files, "game").walkTopDown().count { it.isFile })
    }

    @Test
    fun corruptedFileIsReported() {
        val f = tmp.newFile("bad.zip").apply { writeBytes(ByteArray(2000) { 9 }) }
        try { ZipFileSource(f).use { it.list() }; fail() } catch (e: ImportException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun zipSlipIsRejected() {
        val zip = zipFile("slip.zip", mapOf("SKELDAL.DDL" to 10, "../../evil.so" to 10))
        val files = tmp.newFolder("files")
        try { ZipFileSource(zip).use { importer(files).import(it, "slip.zip") {} }; fail() } catch (e: ImportException) {
            assertTrue(e.message!!.contains("nebezpečnou"))
        }
        assertFalse(File(files, "game").exists())
    }

    @Test
    fun closeRunsCleanup() {
        var cleaned = false
        ZipFileSource(zipFile("c.zip", TestData.steamLayout)) { cleaned = true }.use { it.list() }
        assertTrue(cleaned)
    }
}
