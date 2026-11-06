package cz.gates.skeldal.saves

import cz.gates.skeldal.install.ImportException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Savegame synchronisation through one ZIP file shared by several devices (e.g. on Google Drive,
 * picked with the system file picker - no server, no API keys).
 *
 * The ZIP holds the save files and a small text manifest with the exact modification time of
 * each save, which device wrote it and when each device synchronised last. Merging is per file:
 * the newer version wins, nothing is ever deleted. The caller backs up the local saves first.
 */
class SaveSync(
    private val savesDir: File,
    private val workDir: File,
    private val deviceId: String,
    private val deviceName: String,
) {

    data class Entry(val name: String, val mtime: Long, val size: Long, val device: String)
    data class Device(val id: String, val name: String, val lastSync: Long, val saves: Int)

    /** Content of the sync file (save files are extracted into [dir]). */
    class Remote(val entries: Map<String, Entry>, val devices: List<Device>, val dir: File?) {
        val isEmpty get() = entries.isEmpty()
        fun newest(): Long = entries.values.maxOfOrNull { it.mtime } ?: 0
    }

    data class Plan(
        /** remote saves that are new or newer than the local ones */
        val download: List<Entry>,
        /** local saves that are missing in the sync file or newer than the copies there */
        val upload: List<Entry>,
        val unchanged: Int,
    ) {
        val hasChanges get() = download.isNotEmpty() || upload.isNotEmpty()
    }

    data class Result(val downloaded: Int, val uploaded: Int, val unchanged: Int, val total: Int)

    fun localEntries(): Map<String, Entry> =
        SaveGameManager.saveFiles(savesDir).associate { it.name to Entry(it.name, it.lastModified(), it.length(), deviceId) }

    /** Reads (and extracts) the sync file. An empty or new file gives an empty state. */
    fun readRemote(open: () -> InputStream?): Remote {
        // own folder per read: the status view and a running sync may read at the same time
        val dir = File(workDir, "sync_remote_" + System.nanoTime())
        dir.mkdirs()
        val zipTimes = HashMap<String, Long>()
        var manifest: String? = null
        try {
            open()?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        val name = e.name.substringAfterLast('/').substringAfterLast('\\')
                        if (name == MANIFEST) {
                            manifest = zip.readBytes().toString(Charsets.UTF_8)
                            continue
                        }
                        if (e.name.contains("..") || !SaveGameManager.isSaveName(name)) continue
                        File(dir, name).outputStream().use { o ->
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_TOTAL) throw ImportException("Synchronizační soubor je příliš velký.")
                                o.write(buf, 0, n)
                            }
                        }
                        zipTimes[name] = e.time
                    }
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: ZipException) {
            throw ImportException("Synchronizační soubor není platný ZIP archiv nebo je poškozený.", e)
        }
        val parsed = parseManifest(manifest)
        val entries = HashMap<String, Entry>()
        for (f in SaveGameManager.saveFiles(dir)) {
            val m = parsed.first[f.name]
            entries[f.name] = if (m != null && m.size == f.length()) m
            else Entry(f.name, zipTimes[f.name]?.takeIf { it > 0 } ?: f.lastModified(), f.length(), "")
        }
        return Remote(entries, parsed.second, dir)
    }

    fun plan(remote: Remote, local: Map<String, Entry> = localEntries()): Plan {
        val down = ArrayList<Entry>()
        val up = ArrayList<Entry>()
        var same = 0
        for (name in (remote.entries.keys + local.keys).toSortedSet()) {
            val r = remote.entries[name]
            val l = local[name]
            when {
                r == null -> up.add(l!!)
                l == null -> down.add(r)
                sameVersion(l, r) -> same++
                r.mtime > l.mtime -> down.add(r)
                else -> up.add(l)
            }
        }
        return Plan(down, up, same)
    }

    /**
     * Copies the newer remote saves into the saves folder and writes the merged state into [out].
     * [out] is only opened after everything was read, so an error leaves the sync file untouched.
     */
    fun apply(remote: Remote, plan: Plan, out: () -> OutputStream, now: Long = System.currentTimeMillis()): Result {
        val dir = remote.dir ?: throw IllegalStateException("remote not extracted")
        savesDir.mkdirs()
        for (e in plan.download) {
            val src = File(dir, e.name)
            val dest = File(savesDir, e.name)
            src.copyTo(dest, overwrite = true)
            dest.setLastModified(e.mtime)
        }
        // merged state: remote entries, overwritten by uploaded local ones
        val merged = LinkedHashMap(remote.entries)
        for (e in plan.upload) merged[e.name] = e
        val local = localEntries()
        val devices = remote.devices.filter { it.id != deviceId } +
            Device(deviceId, deviceName, now, local.size)
        out().use { o ->
            ZipOutputStream(o.buffered()).use { z ->
                z.putNextEntry(ZipEntry(MANIFEST))
                z.write(writeManifest(merged.values, devices).toByteArray(Charsets.UTF_8))
                z.closeEntry()
                for (e in merged.values.sortedBy { it.name }) {
                    val uploaded = plan.upload.any { it.name == e.name }
                    val f = if (uploaded) File(savesDir, e.name) else File(dir, e.name)
                    val ze = ZipEntry(e.name)
                    ze.time = e.mtime
                    z.putNextEntry(ze)
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        dir.deleteRecursively()
        return Result(plan.download.size, plan.upload.size, plan.unchanged, merged.size)
    }

    companion object {
        const val MANIFEST = "skeldal-sync.txt"
        private const val HEADER = "skeldal-sync 1"
        private const val MAX_TOTAL = 512L * 1024 * 1024
        /** ZIP and some file systems keep times with 2 s precision */
        private const val TIME_TOLERANCE = 2000L

        fun sameVersion(a: Entry, b: Entry) = a.size == b.size && abs(a.mtime - b.mtime) <= TIME_TOLERANCE

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        fun writeManifest(entries: Collection<Entry>, devices: List<Device>): String = buildString {
            append(HEADER).append('\n')
            for (d in devices) append("device\t${clean(d.id)}\t${d.lastSync}\t${d.saves}\t${clean(d.name)}\n")
            for (e in entries.sortedBy { it.name }) append("file\t${clean(e.name)}\t${e.mtime}\t${e.size}\t${clean(e.device)}\n")
        }

        fun parseManifest(text: String?): Pair<Map<String, Entry>, List<Device>> {
            val files = HashMap<String, Entry>()
            val devices = ArrayList<Device>()
            if (text == null || !text.startsWith(HEADER)) return files to devices
            for (line in text.lineSequence()) {
                val p = line.split('\t')
                when {
                    p.size >= 5 && p[0] == "file" -> {
                        val t = p[2].toLongOrNull() ?: continue
                        val s = p[3].toLongOrNull() ?: continue
                        files[p[1]] = Entry(p[1], t, s, p[4])
                    }
                    p.size >= 5 && p[0] == "device" -> {
                        val t = p[2].toLongOrNull() ?: continue
                        val n = p[3].toIntOrNull() ?: continue
                        devices.add(Device(p[1], p[4], t, n))
                    }
                }
            }
            return files to devices
        }
    }
}
