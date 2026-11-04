package cz.gates.skeldal.install

/** One file of an import source. [path] uses '/' separators and is relative to the source root. */
data class SourceEntry(val path: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** Errors shown to the user as they are (Czech, no technical codes). */
class ImportException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

object PathRules {
    /**
     * Normalizes an archive/tree path to "a/b/c". Returns null for anything that could escape
     * the target directory (absolute paths, drive letters, "..", NUL) - Zip Slip protection.
     */
    fun normalize(raw: String): String? {
        if (raw.isEmpty() || raw.contains('\u0000')) return null
        val unified = raw.replace('\\', '/')
        if (unified.startsWith("/")) return null
        if (unified.length >= 2 && unified[1] == ':') return null
        val parts = ArrayList<String>()
        for (seg in unified.split('/')) {
            when (seg) {
                "", "." -> continue
                ".." -> return null
                else -> parts.add(seg)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /** A single file/directory name coming from the SAF picker. */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')
}
