package cz.gates.skeldal.saves

import java.io.File
import java.io.RandomAccessFile

/**
 * What the launcher can read from a savegame file without the engine (game/gamesave.c):
 *  - file name  "sav.<campaign>.<game time>[-autosave]", both hexadecimal; the campaign number is
 *    the Unix time when the campaign was started
 *  - the first 32 bytes of the file: the save name typed in the game (Kamenický code page)
 */
data class SaveInfo(
    val file: File,
    val name: String,
    val campaign: Long,
    /** in-game time, 360 units per hour */
    val gameTime: Long,
    val autosave: Boolean,
    val savedAt: Long,
) {
    /** "2 dny 14:05" style in-game time (months of 30 days like the game). */
    fun gameTimeText(): String = formatGameTime(gameTime)

    companion object {
        const val NAME_SIZE = 32
        const val AUTOSAVE_SUFFIX = "-autosave"
        private const val HOUR = 360L
        private const val DAY = HOUR * 24
        private const val MONTH = DAY * 30

        fun read(f: File): SaveInfo {
            val base = f.name.removeSuffix(AUTOSAVE_SUFFIX)
            val parts = base.split('.')
            val campaign = parts.getOrNull(1)?.toLongOrNull(16) ?: 0
            val time = parts.getOrNull(2)?.toLongOrNull(16) ?: 0
            val raw = ByteArray(NAME_SIZE)
            val n = try { f.inputStream().use { it.read(raw) } } catch (e: Exception) { 0 }
            val name = decode(raw, n.coerceAtLeast(0))
            return SaveInfo(f, name, campaign, time, f.name.contains(AUTOSAVE_SUFFIX), f.lastModified())
        }

        fun formatGameTime(t: Long): String {
            var c = t
            val months = c / MONTH; c %= MONTH
            val days = c / DAY; c %= DAY
            val hours = c / HOUR; c %= HOUR
            val minutes = c / 6
            val sb = StringBuilder()
            if (months > 0) sb.append(months).append(' ').append(plural(months, "měsíc", "měsíce", "měsíců")).append(' ')
            if (days > 0) sb.append(days).append(' ').append(plural(days, "den", "dny", "dní")).append(' ')
            sb.append(String.format("%d:%02d", hours, minutes))
            return sb.toString()
        }

        private fun plural(n: Long, one: String, few: String, many: String) =
            if (n == 1L) one else if (n in 2..4) few else many

        /** Writes a new save name into the first 32 bytes; the rest of the file is unchanged. */
        fun rename(f: File, newName: String) {
            val bytes = encode(newName)
            RandomAccessFile(f, "rw").use { it.seek(0); it.write(bytes) }
        }

        // Kamenický (CP895) - the code page of the game's fonts
        private val KAM = charArrayOf(
            'Č','ü','é','ď','ä','Ď','Ť','č','ě','Ě','Ĺ','Í','ľ','ĺ','Ä','Á',
            'É','ž','Ž','ô','ö','Ó','ů','Ú','ý','Ö','Ü','Š','Ľ','Ý','Ř','ť',
            'á','í','ó','ú','ň','Ň','Ů','Ô','š','ř','ŕ','Ŕ','¼','§','«','»',
        )

        fun decode(raw: ByteArray, len: Int = raw.size): String {
            val sb = StringBuilder()
            for (i in 0 until minOf(len, raw.size)) {
                val b = raw[i].toInt() and 0xFF
                if (b == 0) break
                sb.append(when {
                    b in 0x80..0xAF -> KAM[b - 0x80]
                    b in 32..126 -> b.toChar()
                    else -> '?'
                })
            }
            return sb.toString().trim()
        }

        fun encode(s: String): ByteArray {
            val out = ByteArray(NAME_SIZE)
            var i = 0
            for (ch in s) {
                if (i >= NAME_SIZE - 1) break
                val b = when {
                    ch.code in 32..126 -> ch.code
                    else -> KAM.indexOf(ch).let { if (it >= 0) 0x80 + it else '?'.code }
                }
                out[i++] = b.toByte()
            }
            return out   // rest stays 0 (terminator)
        }
    }
}
