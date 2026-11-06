package cz.gates.skeldal.saves

import cz.gates.skeldal.install.ImportException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import of savegames as a ZIP file (works with any storage the system picker offers,
 * including Google Drive). Savegame files are those the engine lists (game/gamesave.c,
 * get_all_savegames_callback): names starting with "sav." or ending with ".sav".
 * They are self-contained and named by campaign + game time, so saves from several devices
 * can be merged into one folder.
 */
class SaveGameManager(private val savesDir: File, private val backupDir: File) {

    data class Summary(val count: Int, val newest: Long, val totalBytes: Long)
    data class ImportResult(val imported: Int, val skipped: Int, val backup: File?)

    fun summary(): Summary {
        val files = saveFiles(savesDir)
        return Summary(files.size, files.maxOfOrNull { it.lastModified() } ?: 0, files.sumOf { it.length() })
    }

    /** Writes all savegames into [out] (a ZIP). Returns the number of files. */
    fun export(out: OutputStream, appVersion: String): Int {
        val files = saveFiles(savesDir)
        if (files.isEmpty()) throw ImportException("Zatím nemáte žádné uložené pozice.")
        zipFiles(files, out, appVersion)
        return files.size
    }

    /**
     * Adds the savegames from a ZIP to the saves folder (same names are overwritten).
     * The current saves are first backed up into [backupDir]. Invalid archives change nothing.
     */
    fun import(open: () -> InputStream, appVersion: String): ImportResult =
        withExtracted(open) { incoming, skipped ->
            val backup = backupCurrent(appVersion)
            install(incoming)
            ImportResult(incoming.size, skipped, backup)
        }

    /** Replaces the current savegames with exactly the content of [backup]. */
    fun restore(backup: File, appVersion: String): ImportResult =
        withExtracted({ backup.inputStream() }) { incoming, skipped ->
            val safety = backupCurrent(appVersion)
            for (f in saveFiles(savesDir)) f.delete()
            install(incoming)
            ImportResult(incoming.size, skipped, safety)
        }

    /** Deletes all savegames on this device; they are backed up first. Returns (deleted, backup). */
    fun deleteAll(appVersion: String): Pair<Int, File?> {
        val backup = backupCurrent(appVersion)
        val files = saveFiles(savesDir)
        var n = 0
        for (f in files) if (f.delete()) n++
        return n to backup
    }

    /** Backups made automatically before each import, newest first. */
    fun backups(): List<File> =
        (backupDir.listFiles { f -> f.isFile && f.name.endsWith(".zip") } ?: emptyArray())
            .sortedByDescending { it.name }

    private fun install(incoming: List<File>) {
        savesDir.mkdirs()
        for (f in incoming) {
            val dest = File(savesDir, f.name)
            f.copyTo(dest, overwrite = true)
            dest.setLastModified(f.lastModified())
        }
    }

    /** Extracts the save files of a ZIP into a temporary folder and passes them to [block]. */
    private fun <T> withExtracted(open: () -> InputStream, block: (List<File>, Int) -> T): T {
        val tmp = File(savesDir.parentFile, "saves_import_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            var skipped = 0
            var total = 0L
            try {
                ZipInputStream(open().buffered()).use { zip ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        val name = e.name.substringAfterLast('/').substringAfterLast('\\')
                        if (e.name.contains("..") || !isSaveName(name)) {
                            if (name != MANIFEST) skipped++
                            continue
                        }
                        val target = File(tmp, name)
                        var size = 0L
                        target.outputStream().use { o ->
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                size += n
                                total += n
                                if (size > MAX_FILE || total > MAX_TOTAL) {
                                    throw ImportException("Archiv je příliš velký na to, aby obsahoval uložené pozice.")
                                }
                                o.write(buf, 0, n)
                            }
                        }
                        if (e.time > 0) target.setLastModified(e.time)
                    }
                }
            } catch (e: ImportException) {
                throw e
            } catch (e: ZipException) {
                throw ImportException("Soubor není platný ZIP archiv nebo je poškozený.", e)
            } catch (e: java.io.IOException) {
                throw ImportException("Soubor se nepodařilo přečíst.", e)
            }
            val incoming = saveFiles(tmp)
            if (incoming.isEmpty()) throw ImportException("Vybraný soubor neobsahuje žádné uložené pozice hry.")
            return block(incoming, skipped)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Backs up the current saves (e.g. before a synchronisation). Returns null when there are none. */
    fun backupNow(appVersion: String): File? = backupCurrent(appVersion)

    private fun backupCurrent(appVersion: String): File? {
        val files = saveFiles(savesDir)
        if (files.isEmpty()) return null
        backupDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val f = File(backupDir, "zaloha-$stamp.zip")
        f.outputStream().use { zipFiles(files, it, appVersion) }
        for (old in backups().drop(MAX_BACKUPS)) old.delete()
        return f
    }

    private fun zipFiles(files: List<File>, out: OutputStream, appVersion: String) {
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry(MANIFEST))
            z.write(("Brány Skeldalu - uložené pozice\nverze aplikace: $appVersion\n" +
                "souborů: ${files.size}\nvytvořeno: ${Date()}\n").toByteArray())
            z.closeEntry()
            for (f in files) {
                val e = ZipEntry(f.name)
                e.time = f.lastModified()   // the game sorts saves by file time
                z.putNextEntry(e)
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    companion object {
        const val MANIFEST = "skeldal-saves.txt"
        const val MAX_BACKUPS = 5
        private const val MAX_FILE = 64L * 1024 * 1024
        private const val MAX_TOTAL = 512L * 1024 * 1024

        fun isSaveName(name: String): Boolean {
            if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.startsWith("..")) return false
            val l = name.lowercase(Locale.ROOT)
            return l.startsWith("sav.") || l.endsWith(".sav")
        }

        fun saveFiles(dir: File): List<File> =
            (dir.listFiles { f -> f.isFile && isSaveName(f.name) } ?: emptyArray()).sortedBy { it.name }
    }
}
