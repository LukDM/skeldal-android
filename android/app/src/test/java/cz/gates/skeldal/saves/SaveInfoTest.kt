package cz.gates.skeldal.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveInfoTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsNameCampaignAndTime() {
        val f = File(tmp.root, "sav.6721a3c0.0000b3b0-autosave")
        f.writeBytes(SaveInfo.encode("Hrad Řeka") + ByteArray(100) { 7 })
        val s = SaveInfo.read(f)
        assertEquals("Hrad Řeka", s.name)
        assertEquals(0x6721a3c0L, s.campaign)
        assertEquals(0xb3b0L, s.gameTime)
        assertTrue(s.autosave)
    }

    @Test
    fun gameTimeLikeTheGame() {
        // 360 units = 1 hour, 6 units = 1 minute
        assertEquals("0:00", SaveInfo.formatGameTime(0))
        assertEquals("2:30", SaveInfo.formatGameTime(2 * 360 + 30 * 6))
        assertEquals("1 den 1:00", SaveInfo.formatGameTime(360 * 25))
        assertEquals("1 měsíc 3 dny 0:00", SaveInfo.formatGameTime(360L * 24 * 33))
    }

    @Test
    fun renameKeepsTheRestOfTheFile() {
        val f = File(tmp.root, "sav.00000001.00000001")
        val body = ByteArray(200) { (it % 50).toByte() }
        f.writeBytes(SaveInfo.encode("Staré") + body)
        SaveInfo.rename(f, "Nové jméno žluťoučký")
        assertEquals("Nové jméno žluťoučký", SaveInfo.read(f).name)
        assertEquals(body.toList(), f.readBytes().drop(SaveInfo.NAME_SIZE))
    }
}
