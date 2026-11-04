package cz.gates.skeldal.install

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synthetic installations for tests. Only the file *names* follow the real layout
 * (taken from the engine source and a Steam installation listing); the contents are dummy bytes.
 * No original game data is part of the repository.
 */
object TestData {
    val steamLayout: Map<String, Int> = linkedMapOf(
        "SKELDAL.DDL" to 4096,
        "skeldal.exe" to 100,
        "SDL2.dll" to 100,
        "steam_api64.dll" to 100,
        "skeldal.ini" to 10,
        "wskeldal.ini" to 10,
        "skeldal_en.bat" to 5,
        "TITULKY.ENC" to 30,
        "temp/titulky.dec" to 30,
        "maps/LESPRED.MAP" to 500,
        "maps/SKELDAL.MAP" to 500,
        "maps/ITEMS.DAT" to 200,
        "music/TRACK01.MUS" to 300,
        "music/TRACK02.MUS" to 300,
        "video/INTRO.MGF" to 800,
        "lang/en/gfx.ddl" to 400,
        "lang/en/ui.csv" to 40,
    )

    fun entries(layout: Map<String, Int>, prefix: String = ""): List<SourceEntry> =
        layout.map { (p, s) -> SourceEntry(prefix + p, s.toLong()) }

    fun writeDir(root: File, layout: Map<String, Int>) {
        for ((p, s) in layout) {
            val f = File(root, p)
            f.parentFile.mkdirs()
            f.writeBytes(ByteArray(s) { (it % 251).toByte() })
        }
    }

    fun zip(layout: Map<String, Int>, prefix: String = ""): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((p, s) in layout) {
                z.putNextEntry(ZipEntry(prefix + p))
                z.write(ByteArray(s) { (it % 251).toByte() })
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
