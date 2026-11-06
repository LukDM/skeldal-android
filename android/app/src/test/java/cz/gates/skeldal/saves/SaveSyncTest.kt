package cz.gates.skeldal.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class SaveSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun device(name: String): Pair<File, SaveSync> {
        val saves = tmp.newFolder("$name-saves")
        return saves to SaveSync(saves, tmp.newFolder("$name-work"), "id-$name", name)
    }

    private fun save(dir: File, name: String, text: String, time: Long) =
        File(dir, name).apply { writeText(text); setLastModified(time) }

    /** One synchronisation of a device against the shared file (bytes in memory). */
    private fun syncOnce(s: SaveSync, shared: ByteArray?, now: Long): Pair<ByteArray, SaveSync.Result> {
        val remote = s.readRemote { shared?.inputStream() }
        val plan = s.plan(remote)
        val out = ByteArrayOutputStream()
        val r = s.apply(remote, plan, { out }, now)
        return out.toByteArray() to r
    }

    @Test
    fun firstDeviceUploadsEverything() {
        val (dirA, a) = device("A")
        save(dirA, "sav.00000001.0001", "a1", 1_000_000)
        save(dirA, "sav.00000001.0002", "a2", 2_000_000)
        val (shared, r) = syncOnce(a, null, 10_000_000)
        assertEquals(2, r.uploaded)
        assertEquals(0, r.downloaded)
        val remote = a.readRemote { shared.inputStream() }
        assertEquals(2, remote.entries.size)
        assertEquals(1, remote.devices.size)
        assertEquals("A", remote.devices[0].name)
        assertEquals(2_000_000, remote.entries["sav.00000001.0002"]!!.mtime)
    }

    @Test
    fun newerVersionWinsAndNothingIsDeleted() {
        val (dirA, a) = device("A")
        val (dirB, b) = device("B")
        save(dirA, "sav.00000001.0001", "old", 1_000_000)
        save(dirA, "sav.00000001.only-a", "a", 1_500_000)
        var (shared, _) = syncOnce(a, null, 3_000_000)

        save(dirB, "sav.00000001.0001", "new from B", 5_000_000)
        save(dirB, "sav.00000001.only-b", "b", 4_000_000)
        val rb = syncOnce(b, shared, 6_000_000)
        shared = rb.first
        assertEquals(1, rb.second.downloaded)   // only-a
        assertEquals(2, rb.second.uploaded)     // newer 0001 + only-b
        assertEquals("a", File(dirB, "sav.00000001.only-a").readText())
        assertEquals(1_500_000, File(dirB, "sav.00000001.only-a").lastModified())

        val ra = syncOnce(a, shared, 7_000_000)
        assertEquals(2, ra.second.downloaded)
        assertEquals("new from B", File(dirA, "sav.00000001.0001").readText())
        assertTrue(File(dirA, "sav.00000001.only-b").exists())
        assertTrue(File(dirA, "sav.00000001.only-a").exists())

        // both devices are now in sync
        val remote = a.readRemote { ra.first.inputStream() }
        assertFalse(a.plan(remote).hasChanges)
        assertFalse(b.plan(b.readRemote { ra.first.inputStream() }).hasChanges)
        assertEquals(setOf("A", "B"), remote.devices.map { it.name }.toSet())
    }

    @Test
    fun manifestRoundTrip() {
        val e = listOf(SaveSync.Entry("sav.1", 123, 4, "dev\tx"))
        val d = listOf(SaveSync.Device("id", "Telefon\nPoco", 99, 7))
        val (files, devices) = SaveSync.parseManifest(SaveSync.writeManifest(e, d))
        assertEquals(SaveSync.Entry("sav.1", 123, 4, "dev x"), files["sav.1"])
        assertEquals(SaveSync.Device("id", "Telefon Poco", 99, 7), devices[0])
    }

    @Test
    fun plainExportZipWorksAsSyncFile() {
        val (dirA, a) = device("A")
        save(dirA, "sav.00000002.0001", "x", 2_000_000)
        val out = ByteArrayOutputStream()
        SaveGameManager(dirA, tmp.newFolder("bk")).export(out, "test")
        val (_, b) = device("B")
        val remote = b.readRemote { out.toByteArray().inputStream() }
        assertEquals(1, b.plan(remote).download.size)
    }

    @Test
    fun downloadOnlyLeavesTheSyncFileAndUploadsNothing() {
        val (dirA, a) = device("A")
        val (dirB, b) = device("B")
        save(dirA, "sav.00000003.0001", "from A", 1_000_000)
        val (shared, _) = syncOnce(a, null, 2_000_000)
        save(dirB, "sav.00000003.only-b", "b", 3_000_000)
        val remote = b.readRemote { shared.inputStream() }
        val plan = b.plan(remote)
        assertEquals(1, plan.download.size)
        assertEquals(1, plan.upload.size)
        assertEquals(1, b.downloadOnly(remote, plan))
        assertEquals("from A", File(dirB, "sav.00000003.0001").readText())
        // the shared file still has only A's save
        assertEquals(setOf("sav.00000003.0001"), a.readRemote { shared.inputStream() }.entries.keys)
    }
}
