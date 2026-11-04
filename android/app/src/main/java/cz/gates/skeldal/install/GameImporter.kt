package cz.gates.skeldal.install

import java.io.File

/**
 * Import pipeline: list -> validate -> check space -> copy into a temporary directory ->
 * verify -> replace the previous installation. A failure at any step keeps the old data.
 * Must run on a background thread.
 */
class GameImporter(
    private val repo: InstallationRepository,
    private val workDir: File,
    private val freeSpace: () -> Long,
) {
    enum class Phase { LISTING, VALIDATING, COPYING, VERIFYING }

    data class Progress(val phase: Phase, val copiedBytes: Long = 0, val totalBytes: Long = 0)

    fun import(source: ImportSource, sourceName: String, onProgress: (Progress) -> Unit): InstallationRepository.Installation {
        onProgress(Progress(Phase.LISTING))
        val listing = source.list()

        onProgress(Progress(Phase.VALIDATING))
        val result = GameInstallationDetector.detect(listing)
        if (result.status == GameInstallationDetector.Status.INVALID) {
            throw ImportException(
                "Ve vybraném umístění nebyl nalezen soubor ${GameInstallationDetector.DDL}. " +
                    "Vyberte složku (nebo ZIP) s instalací hry Brány Skeldalu."
            )
        }

        val total = result.totalBytes
        val needed = total + SAFETY_MARGIN
        val available = freeSpace()
        if (available < needed) {
            throw ImportException(
                "Není dost volného místa. Potřeba cca ${needed / MB} MB, dostupných ${available / MB} MB."
            )
        }

        val tmp = File(workDir, "game_import_tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw ImportException("Nelze vytvořit dočasnou složku pro import.")

        try {
            val wanted = result.files.associateBy { result.rootPrefix + it.path }
            var copied = 0L
            val buffer = ByteArray(64 * 1024)
            onProgress(Progress(Phase.COPYING, 0, total))
            source.copy(wanted.keys) { srcPath, input ->
                val rel = wanted.getValue(srcPath).path
                val target = resolveInside(tmp, rel)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        onProgress(Progress(Phase.COPYING, copied, total))
                    }
                }
            }

            onProgress(Progress(Phase.VERIFYING, copied, total))
            for (f in result.files) {
                val t = File(tmp, f.path)
                if (!t.isFile || t.length() != f.size) {
                    throw ImportException("Soubor ${f.path} se nezkopíroval celý. Zkuste import znovu.")
                }
            }

            val dest = repo.gameDir
            val old = File(workDir, "game_old")
            old.deleteRecursively()
            if (dest.exists() && !dest.renameTo(old)) {
                throw ImportException("Předchozí data nelze nahradit.")
            }
            if (!tmp.renameTo(dest)) {
                old.renameTo(dest) // roll back
                throw ImportException("Data se nepodařilo uložit do úložiště aplikace.")
            }
            old.deleteRecursively()

            val inst = InstallationRepository.Installation(
                id = "default",
                type = result.sourceType,
                status = result.status,
                sourceName = sourceName,
                importedAt = System.currentTimeMillis(),
                fileCount = result.files.size,
                totalBytes = total,
                musicTracks = result.musicTracks,
                hasIntroVideo = result.hasIntroVideo,
                languages = result.languages,
                missingExpected = result.missingExpected,
            )
            repo.save(inst)
            return inst
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Re-checks the already imported files (button "Zkontrolovat soubory"). */
    fun verifyInstalled(): GameInstallationDetector.Result? {
        if (!repo.gameDir.isDirectory) return null
        val result = GameInstallationDetector.detect(DirectorySource(repo.gameDir).list())
        val prev = repo.load()
            ?: InstallationRepository.Installation(   // imported by an older app version
                id = "default", type = result.sourceType, status = result.status,
                sourceName = "", importedAt = repo.gameDir.lastModified(), fileCount = 0, totalBytes = 0,
                musicTracks = 0, hasIntroVideo = false, languages = emptyList(), missingExpected = emptyList(),
            )
        run {
            repo.save(
                prev.copy(
                    type = if (prev.type == GameInstallationDetector.SourceType.UNKNOWN) result.sourceType else prev.type,
                    status = result.status,
                    fileCount = result.files.size,
                    totalBytes = result.totalBytes,
                    musicTracks = result.musicTracks,
                    hasIntroVideo = result.hasIntroVideo,
                    languages = result.languages,
                    missingExpected = result.missingExpected + result.missingRequired,
                )
            )
        }
        return result
    }

    /** Protection against path traversal: the target must stay inside [root]. */
    private fun resolveInside(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.canonicalPath + File.separator)) {
            throw ImportException("Neplatná cesta v souboru: $relative")
        }
        return target
    }

    companion object {
        const val MB = 1024L * 1024L
        const val SAFETY_MARGIN = 100L * MB
    }
}
