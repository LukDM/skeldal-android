package cz.gates.skeldal.install

import java.io.File
import java.io.InputStream

/**
 * Corrected game data files from the engine repository (MIT licensed, data/ in the repository,
 * packed into the APK as assets).
 *
 * BILA_VEZ.MAP (White Tower): the original map has a script error in the logic puzzle on the
 * top floor; the engine repository contains a corrected map that only works with the new engine.
 * Maps are always loose files in the maps folder (load_map in game/realgame.c, fopen_icase), so
 * replacing the file is enough. The original is kept in [backupDir].
 */
class GameFixes(
    private val gameDir: File,
    private val backupDir: File,
    /** opens a bundled fixed file by its path relative to the game root, e.g. "maps/BILA_VEZ.MAP" */
    private val open: (String) -> InputStream?,
) {

    /** Applies all fixes; returns the paths that were replaced now (empty = already up to date). */
    fun apply(): List<String> {
        val done = ArrayList<String>()
        for (path in FIXES) {
            val fixed = (try { open(path) } catch (e: Exception) { null })?.use { it.readBytes() } ?: continue
            val target = findIcase(gameDir, path) ?: continue      // file not in this installation
            if (target.length() == fixed.size.toLong() && target.readBytes().contentEquals(fixed)) continue
            backupDir.mkdirs()
            val backup = File(backupDir, target.name)
            if (!backup.exists()) target.copyTo(backup)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(fixed)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) { tmp.delete(); continue }
            }
            done.add(path)
        }
        return done
    }

    companion object {
        val FIXES = listOf("maps/BILA_VEZ.MAP")

        /** Finds "a/b/C.EXT" under [root] ignoring case of every path element (like the engine). */
        fun findIcase(root: File, path: String): File? {
            var dir = root
            val parts = path.split('/')
            for ((i, p) in parts.withIndex()) {
                val exact = File(dir, p)
                val next = if (exact.exists()) exact
                else dir.listFiles()?.firstOrNull { it.name.equals(p, ignoreCase = true) } ?: return null
                if (i == parts.size - 1) return next.takeIf { it.isFile }
                if (!next.isDirectory) return null
                dir = next
            }
            return null
        }
    }
}
