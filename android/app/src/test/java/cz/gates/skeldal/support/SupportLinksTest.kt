package cz.gates.skeldal.support

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SupportLinksTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, ZoneId.of("Europe/Prague"))

    @Test fun countdown() {
        assertEquals(23, SupportLinks.campaignDaysLeft(at(2026, 11, 9)))   // Startovač on 9. 11.: 23 days left
        assertEquals(0, SupportLinks.campaignDaysLeft(at(2026, 12, 2, 23)))
        assertEquals(-1, SupportLinks.campaignDaysLeft(at(2026, 12, 3, 0)))
    }

    @Test fun czechPlural() {
        assertEquals("končí dnes", SupportLinks.daysText(0))
        assertEquals("zbývá 1 den", SupportLinks.daysText(1))
        assertEquals("zbývají 3 dny", SupportLinks.daysText(3))
        assertEquals("zbývá 23 dní", SupportLinks.daysText(23))
    }
}
