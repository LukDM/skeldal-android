package cz.gates.skeldal.install

import java.io.File
import java.util.Properties

/**
 * Stores what was imported. One installation ("default") for now; the model has an id so that
 * more installations (DOS / Windows / Steam) can be added later without changing the format.
 * The game files themselves are in [gameDir]; savegames are elsewhere and are never touched here.
 */
class InstallationRepository(private val filesDir: File) {

    data class Installation(
        val id: String,
        val type: GameInstallationDetector.SourceType,
        val status: GameInstallationDetector.Status,
        val sourceName: String,
        val importedAt: Long,
        val fileCount: Int,
        val totalBytes: Long,
        val musicTracks: Int,
        val hasIntroVideo: Boolean,
        val languages: List<String>,
        val missingExpected: List<String>,
    )

    val gameDir: File get() = File(filesDir, "game")
    private val metaFile: File get() = File(filesDir, "installations/default.properties")

    fun hasGameFiles(): Boolean = File(gameDir, GameInstallationDetector.DDL).isFile

    fun load(): Installation? {
        val f = metaFile
        if (!f.isFile) return null
        return try {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            Installation(
                id = p.getProperty("id", "default"),
                type = enumOr(p.getProperty("type"), GameInstallationDetector.SourceType.UNKNOWN),
                status = enumOr(p.getProperty("status"), GameInstallationDetector.Status.VALID),
                sourceName = p.getProperty("sourceName", ""),
                importedAt = p.getProperty("importedAt", "0").toLongOrNull() ?: 0,
                fileCount = p.getProperty("fileCount", "0").toIntOrNull() ?: 0,
                totalBytes = p.getProperty("totalBytes", "0").toLongOrNull() ?: 0,
                musicTracks = p.getProperty("musicTracks", "0").toIntOrNull() ?: 0,
                hasIntroVideo = p.getProperty("hasIntroVideo", "false").toBoolean(),
                languages = splitList(p.getProperty("languages", "")),
                missingExpected = splitList(p.getProperty("missingExpected", "")),
            )
        } catch (e: Exception) {
            null // corrupted metadata: treated as "unknown", the files are re-checked
        }
    }

    fun save(inst: Installation) {
        val p = Properties()
        p.setProperty("schemaVersion", SCHEMA_VERSION.toString())
        p.setProperty("id", inst.id)
        p.setProperty("type", inst.type.name)
        p.setProperty("status", inst.status.name)
        p.setProperty("sourceName", inst.sourceName)
        p.setProperty("importedAt", inst.importedAt.toString())
        p.setProperty("fileCount", inst.fileCount.toString())
        p.setProperty("totalBytes", inst.totalBytes.toString())
        p.setProperty("musicTracks", inst.musicTracks.toString())
        p.setProperty("hasIntroVideo", inst.hasIntroVideo.toString())
        p.setProperty("languages", inst.languages.joinToString(","))
        p.setProperty("missingExpected", inst.missingExpected.joinToString(","))
        metaFile.parentFile?.mkdirs()
        val tmp = File(metaFile.path + ".tmp")
        tmp.outputStream().use { p.store(it, "Skeldal installation") }
        if (!tmp.renameTo(metaFile)) {
            metaFile.delete()
            tmp.renameTo(metaFile)
        }
    }

    /** Removes imported game data. Savegames (filesDir/saves) are kept. */
    fun delete() {
        gameDir.deleteRecursively()
        metaFile.delete()
    }

    private fun splitList(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun <reified T : Enum<T>> enumOr(v: String?, def: T): T =
        try { if (v == null) def else enumValueOf<T>(v) } catch (e: IllegalArgumentException) { def }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
