package cz.gates.skeldal.install

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * ZIP read through its central directory (java.util.zip.ZipFile). Unlike a stream reader it
 * handles every common ZIP variant (entries "stored" with a data descriptor, archives made by
 * Windows Explorer, 7-Zip, macOS, Android file managers ...) and gives exact file sizes up front.
 *
 * File names without the UTF-8 flag are decoded with the code page of the tool that created
 * the archive; Windows Explorer uses the OEM code page (CP852 for Czech, CP437 for English), so
 * names like "Brány Skeldalu" need a fallback.
 */
class ZipFileSource(private val file: File, private val onClose: () -> Unit = {}) : ImportSource, AutoCloseable {

    private var zip: ZipFile? = null
    private val entryByPath = HashMap<String, String>()   // normalized path -> entry name

    override fun list(): List<SourceEntry> {
        var lastError: Exception? = null
        for (cs in CHARSETS) {
            try {
                val z = ZipFile(file, cs)
                val out = ArrayList<SourceEntry>()
                entryByPath.clear()
                for (e in z.entries()) {
                    if (e.isDirectory) continue
                    val path = PathRules.normalize(e.name)
                        ?: throw ImportException("Archiv obsahuje nebezpečnou cestu (${e.name}) a nebyl importován.")
                    if (entryByPath.put(path, e.name) == null) out.add(SourceEntry(path, e.size.coerceAtLeast(0)))
                }
                zip?.close()
                zip = z
                if (out.isEmpty()) throw ImportException("Archiv je prázdný.")
                return out
            } catch (e: ImportException) {
                throw e
            } catch (e: IllegalArgumentException) {
                lastError = e      // names not valid in this charset -> try the next one
            } catch (e: ZipException) {
                if (e.message?.contains("MALFORMED", ignoreCase = true) == true || e.message?.contains("invalid", true) == true) {
                    lastError = e
                } else {
                    throw ImportException("Soubor není platný ZIP archiv nebo je poškozený.", e)
                }
            } catch (e: IOException) {
                throw ImportException("Archiv se nepodařilo přečíst.", e)
            }
        }
        throw ImportException("Názvy souborů v archivu mají nepodporované kódování.", lastError)
    }

    override fun copy(paths: Set<String>, sink: (String, InputStream) -> Unit) {
        val z = zip ?: throw IllegalStateException("list() first")
        try {
            for (p in paths) {
                val e = z.getEntry(entryByPath[p] ?: continue) ?: continue
                z.getInputStream(e).use { sink(p, NonClosing(it)) }
            }
        } catch (e: ZipException) {
            throw ImportException("Archiv ZIP je poškozený (chyba při rozbalování).", e)
        }
    }

    override fun close() {
        try { zip?.close() } catch (e: IOException) { }
        zip = null
        onClose()
    }

    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    companion object {
        /** UTF-8 first (flagged names are always UTF-8), then Windows Explorer code pages. */
        val CHARSETS: List<Charset> = listOfNotNull(
            Charsets.UTF_8,
            runCatching { Charset.forName("IBM852") }.getOrNull(),
            runCatching { Charset.forName("IBM437") }.getOrNull(),
            Charsets.ISO_8859_1,   // accepts any byte, last resort
        )
    }
}
