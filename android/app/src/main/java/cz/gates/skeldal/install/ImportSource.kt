package cz.gates.skeldal.install

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Something the user selected: a folder (SAF tree) or a ZIP archive. */
interface ImportSource {
    /** Lists all files. Paths are normalized ("a/b"), see [PathRules]. */
    fun list(): List<SourceEntry>

    /** Streams the files whose path is in [paths]; the stream passed to [sink] must not be closed by it. */
    fun copy(paths: Set<String>, sink: (path: String, input: InputStream) -> Unit)
}

/**
 * ZIP read sequentially from a stream (works with any SAF Uri, no temporary copy).
 * The archive is read twice: once to list and measure entries, once to extract.
 */
class ZipStreamSource(private val open: () -> InputStream) : ImportSource {

    override fun list(): List<SourceEntry> {
        val out = ArrayList<SourceEntry>()
        val seen = HashSet<String>()
        read { zip ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val path = PathRules.normalize(e.name)
                    ?: throw ImportException("Archiv obsahuje nebezpečnou cestu (${e.name}) a nebyl importován.")
                var size = 0L
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    size += n
                }
                if (seen.add(path)) out.add(SourceEntry(path, size))
            }
        }
        if (out.isEmpty()) throw ImportException("Archiv je prázdný nebo to není ZIP soubor.")
        return out
    }

    override fun copy(paths: Set<String>, sink: (String, InputStream) -> Unit) {
        val done = HashSet<String>()
        read { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val path = PathRules.normalize(e.name) ?: continue
                if (path in paths && done.add(path)) sink(path, NonClosing(zip))
            }
        }
    }

    private fun read(block: (ZipInputStream) -> Unit) {
        try {
            ZipInputStream(open().buffered()).use(block)
        } catch (e: ImportException) {
            throw e
        } catch (e: ZipException) {
            throw ImportException("Archiv ZIP je poškozený nebo nepodporovaný.", e)
        } catch (e: IOException) {
            throw ImportException("Archiv se nepodařilo přečíst.", e)
        } catch (e: IllegalArgumentException) {
            // malformed entry names (bad encoding)
            throw ImportException("Archiv ZIP je poškozený nebo nepodporovaný.", e)
        }
    }

    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }
}

/** A plain directory on the local file system (used for re-checking an installed game and in tests). */
class DirectorySource(private val root: java.io.File) : ImportSource {
    override fun list(): List<SourceEntry> =
        root.walkTopDown().filter { it.isFile }
            .map { SourceEntry(it.relativeTo(root).invariantSeparatorsPath, it.length()) }
            .toList()

    override fun copy(paths: Set<String>, sink: (String, InputStream) -> Unit) {
        for (p in paths) java.io.File(root, p).inputStream().use { sink(p, it) }
    }
}
