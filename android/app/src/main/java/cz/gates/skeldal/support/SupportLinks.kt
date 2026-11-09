package cz.gates.skeldal.support

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Links to support the authors (shown in the launcher's Informace and in the campaign notice). */
object SupportLinks {
    /** Crowdfunding of Brány Skeldalu 4 on Startovač. */
    const val CAMPAIGN_URL = "https://www.startovac.cz/projekty/brany-skeldalu-4"
    /** Last day of the campaign (23 days left on 9. 11. 2026); the notice disappears after it. */
    val CAMPAIGN_LAST_DAY: LocalDate = LocalDate.of(2026, 12, 2)
    private val ZONE: ZoneId = ZoneId.of("Europe/Prague")

    /** The game this port runs (current PC release) on Steam. */
    const val STEAM_URL = "https://store.steampowered.com/app/3533830/Br%C3%A1ny_Skeldalu/"

    /** Support for the author of the Android port; empty = no button. */
    const val AUTHOR_URL = ""

    /** Whole days until the last day like Startovač counts them (0 = the last day), -1 = over. */
    fun campaignDaysLeft(now: ZonedDateTime = ZonedDateTime.now(ZONE)): Int {
        val today = now.withZoneSameInstant(ZONE).toLocalDate()
        val d = ChronoUnit.DAYS.between(today, CAMPAIGN_LAST_DAY).toInt()
        return if (d < 0) -1 else d
    }

    val campaignRunning: Boolean get() = campaignDaysLeft() >= 0

    /** "končí dnes" / "zbývá 1 den" / "zbývají 3 dny" / "zbývá 23 dní" */
    fun daysText(n: Int): String = when {
        n <= 0 -> "končí dnes"
        n == 1 -> "zbývá 1 den"
        n in 2..4 -> "zbývají $n dny"
        else -> "zbývá $n dní"
    }
}
