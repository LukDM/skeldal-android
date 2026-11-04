package cz.gates.skeldal.install

/**
 * Decides whether a list of files is a usable installation of the game.
 *
 * Rules come from the engine source (game/skeldal.c, libs/memman.c):
 *  - SKELDAL.DDL in the data root is mandatory (init_DDL_manager aborts without it).
 *  - maps/LESPRED.MAP is the default start map (default_map in skeldal.c).
 *  - music/ (TRACKxx.MUS), video/ (INTRO.MGF) and lang/<id>/ are optional.
 * File names are matched case-insensitively, like the engine (file_icase_find / fopen_icase).
 * Data files may also live inside SKELDAL.DDL, so only the files above are checked.
 */
object GameInstallationDetector {

    enum class Status { VALID, INCOMPLETE, INVALID }

    enum class SourceType(val label: String) {
        STEAM("Steam"),
        GOG("GOG"),
        PC_SDL("PC verze (SDL)"),
        /** Data of the current PC release (Steam / GOG); no executables in the source. */
        PC_NEW("PC verze (Steam / GOG)"),
        ORIGINAL("Původní DOS/Windows verze"),
        UNKNOWN("Neznámá verze"),
    }

    data class Result(
        val status: Status,
        /** Prefix of the game root inside the source ("" or e.g. "Brány Skeldalu/"). */
        val rootPrefix: String,
        val sourceType: SourceType,
        /** Game files to copy, paths relative to the game root. */
        val files: List<SourceEntry>,
        /** Files intentionally not copied (executables, libraries, configs, temp). */
        val skipped: List<String>,
        val missingRequired: List<String>,
        val missingExpected: List<String>,
        val musicTracks: Int,
        val hasIntroVideo: Boolean,
        val languages: List<String>,
    ) {
        val totalBytes: Long get() = files.sumOf { it.size }
    }

    const val DDL = "SKELDAL.DDL"
    const val DEFAULT_MAP = "maps/LESPRED.MAP"

    private val SKIPPED_EXTENSIONS = setOf("exe", "dll", "bat", "ini", "so", "pdb", "lnk")
    private val SKIPPED_DIRS = setOf("temp")

    fun detect(entries: List<SourceEntry>): Result {
        val ddl = entries
            .filter { it.name.equals(DDL, ignoreCase = true) }
            .minWithOrNull(compareBy<SourceEntry> { it.path.count { c -> c == '/' } }.thenBy { it.path })

        if (ddl == null) {
            return Result(
                Status.INVALID, "", detectType(entries.map { it.name }), emptyList(), emptyList(),
                listOf(DDL), emptyList(), 0, false, emptyList()
            )
        }

        val rootPrefix = ddl.path.substring(0, ddl.path.length - ddl.name.length)
        val underRoot = entries.filter { it.path.startsWith(rootPrefix) }
            .map { it.copy(path = it.path.substring(rootPrefix.length)) }

        val type = detectType(underRoot.filter { !it.path.contains('/') }.map { it.name }).let {
            if (it == SourceType.UNKNOWN && hasNewStringTables(underRoot)) SourceType.PC_NEW else it
        }

        val files = ArrayList<SourceEntry>()
        val skipped = ArrayList<String>()
        for (e in underRoot) {
            if (isSkipped(e.path)) skipped.add(e.path) else files.add(e)
        }

        val lower = files.map { it.path.lowercase() }.toSet()
        val missingExpected = ArrayList<String>()
        if (DEFAULT_MAP.lowercase() !in lower) missingExpected.add(DEFAULT_MAP)

        val music = files.count {
            it.path.lowercase().startsWith("music/") && it.name.lowercase().endsWith(".mus")
        }
        val intro = "video/intro.mgf" in lower
        val languages = files.mapNotNull {
            val p = it.path.split('/')
            if (p.size >= 3 && p[0].equals("lang", ignoreCase = true)) p[1] else null
        }.distinct().sorted()

        val status = if (missingExpected.isEmpty()) Status.VALID else Status.INCOMPLETE
        return Result(status, rootPrefix, type, files, skipped, emptyList(), missingExpected, music, intro, languages)
    }

    private fun isSkipped(relPath: String): Boolean {
        val first = relPath.substringBefore('/', "")
        if (first.isNotEmpty() && first.lowercase() in SKIPPED_DIRS) return true
        val ext = relPath.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext in SKIPPED_EXTENSIONS
    }

    /**
     * The current PC release (Steam / GOG) ships language folders with CSV string tables
     * (CSV files in lang/<id>), which the original DOS / Windows versions do not have.
     */
    private fun hasNewStringTables(files: List<SourceEntry>): Boolean = files.any {
        val p = it.path.lowercase()
        p.startsWith("lang/") && p.count { c -> c == '/' } == 2 && p.endsWith(".csv")
    }

    /** Heuristic, only for display. Based on files next to SKELDAL.DDL. */
    private fun detectType(rootNames: List<String>): SourceType {
        val n = rootNames.map { it.lowercase() }.toSet()
        return when {
            "steam_api64.dll" in n || "steam_api.dll" in n || "steam_appid.txt" in n -> SourceType.STEAM
            "galaxy64.dll" in n || "galaxy.dll" in n -> SourceType.GOG
            "sdl2.dll" in n -> SourceType.PC_SDL
            n.any { it.endsWith(".exe") } -> SourceType.ORIGINAL
            else -> SourceType.UNKNOWN
        }
    }
}
