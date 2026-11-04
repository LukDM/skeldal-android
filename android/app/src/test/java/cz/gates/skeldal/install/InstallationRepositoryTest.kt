package cz.gates.skeldal.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallationRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = InstallationRepository.Installation(
        "default", GameInstallationDetector.SourceType.STEAM, GameInstallationDetector.Status.VALID,
        "Brány Skeldalu", 1234L, 10, 999L, 13, true, listOf("en"), emptyList()
    )

    @Test
    fun saveAndLoad() {
        val repo = InstallationRepository(tmp.root)
        assertNull(repo.load())
        repo.save(sample)
        assertEquals(sample, repo.load())
    }

    @Test
    fun corruptedMetadataIsIgnored() {
        val repo = InstallationRepository(tmp.root)
        File(tmp.root, "installations").mkdirs()
        File(tmp.root, "installations/default.properties").writeBytes(byteArrayOf(0, 1, 2, '\\'.code.toByte(), 'u'.code.toByte()))
        assertNull(repo.load())
    }

    @Test
    fun unknownEnumValuesFallBack() {
        val repo = InstallationRepository(tmp.root)
        repo.save(sample)
        val f = File(tmp.root, "installations/default.properties")
        f.writeText(f.readText().replace("type=STEAM", "type=FUTURE_STORE"))
        assertEquals(GameInstallationDetector.SourceType.UNKNOWN, repo.load()!!.type)
    }

    @Test
    fun deleteKeepsSaves() {
        val repo = InstallationRepository(tmp.root)
        File(repo.gameDir, "SKELDAL.DDL").apply { parentFile.mkdirs(); writeText("x") }
        File(tmp.root, "saves/s.sav").apply { parentFile.mkdirs(); writeText("s") }
        repo.save(sample)
        assertTrue(repo.hasGameFiles())
        repo.delete()
        assertFalse(repo.hasGameFiles())
        assertNull(repo.load())
        assertTrue(File(tmp.root, "saves/s.sav").isFile)
    }
}
