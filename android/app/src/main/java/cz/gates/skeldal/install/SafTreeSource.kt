package cz.gates.skeldal.install

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/** Folder chosen with the system picker (ACTION_OPEN_DOCUMENT_TREE). */
class SafTreeSource(private val context: Context, treeUri: Uri) : ImportSource {

    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw ImportException("Vybranou složku nelze otevřít.")
    private val byPath = HashMap<String, DocumentFile>()

    override fun list(): List<SourceEntry> {
        byPath.clear()
        val out = ArrayList<SourceEntry>()
        walk(root, "", out)
        if (out.isEmpty()) throw ImportException("Ve vybrané složce nejsou žádné soubory.")
        return out
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<SourceEntry>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (!PathRules.isSafeName(name)) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, path, out)
            } else if (child.isFile) {
                byPath[path] = child
                out.add(SourceEntry(path, child.length()))
            }
        }
    }

    override fun copy(paths: Set<String>, sink: (String, InputStream) -> Unit) {
        for (p in paths) {
            val doc = byPath[p] ?: throw ImportException("Soubor zmizel během importu: $p")
            val input = context.contentResolver.openInputStream(doc.uri)
                ?: throw ImportException("Soubor nelze přečíst: $p")
            input.use { sink(p, it) }
        }
    }
}
