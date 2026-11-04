package cz.gates.skeldal.saves

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import cz.gates.skeldal.R
import cz.gates.skeldal.install.ImportException
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Savegame export / import (e.g. to move a game between devices or to Google Drive). */
class SavesActivity : Activity() {

    private lateinit var manager: SaveGameManager
    private lateinit var info: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: List<Button>
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saves)
        manager = manager(this)
        info = findViewById(R.id.saves_info)
        progress = findViewById(R.id.saves_progress)
        val export = findViewById<Button>(R.id.btn_saves_export)
        val import = findViewById<Button>(R.id.btn_saves_import)
        val restore = findViewById<Button>(R.id.btn_saves_restore)
        val close = findViewById<Button>(R.id.btn_saves_close)
        buttons = listOf(export, import, restore, close)

        export.setOnClickListener {
            val name = "skeldal-pozice-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date()) + ".zip"
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name),
                REQ_EXPORT
            )
        }
        import.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")),
                REQ_IMPORT
            )
        }
        restore.setOnClickListener { chooseBackup() }
        close.setOnClickListener { finish() }
        export.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val s = manager.summary()
        info.text = if (s.count == 0) getString(R.string.saves_none)
        else getString(R.string.saves_summary, s.count,
            DateFormat.getDateTimeInstance().format(Date(s.newest)), (s.totalBytes + 1023) / 1024)
        findViewById<Button>(R.id.btn_saves_export).isEnabled = s.count > 0
        findViewById<Button>(R.id.btn_saves_restore).isEnabled = manager.backups().isNotEmpty()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> background {
                val out = contentResolver.openOutputStream(uri, "wt") ?: throw ImportException("Soubor nelze vytvořit.")
                val n = out.use { manager.export(it, version()) }
                getString(R.string.saves_exported, n)
            }
            REQ_IMPORT -> background {
                val r = manager.import({ contentResolver.openInputStream(uri) ?: throw ImportException("Soubor nelze otevřít.") }, version())
                getString(R.string.saves_imported, r.imported) + if (r.backup != null) "\n\n" + getString(R.string.saves_backup_made) else ""
            }
        }
    }

    private fun chooseBackup() {
        val list = manager.backups()
        if (list.isEmpty()) return
        val labels = list.map { DateFormat.getDateTimeInstance().format(Date(it.lastModified())) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.saves_restore_title)
            .setItems(labels) { _, i -> confirmRestore(list[i], labels[i]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRestore(f: File, label: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.saves_restore_title)
            .setMessage(getString(R.string.saves_restore_confirm, label))
            .setPositiveButton(R.string.saves_restore_do) { _, _ ->
                background {
                    val r = manager.restore(f, version())
                    getString(R.string.saves_restored, r.imported)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Runs file work off the UI thread and shows the result or a readable error. */
    private fun background(work: () -> String) {
        setBusy(true)
        Thread {
            val msg = try {
                work()
            } catch (e: ImportException) {
                e.message ?: getString(R.string.saves_failed)
            } catch (e: SecurityException) {
                getString(R.string.error_access_denied)
            } catch (e: Exception) {
                getString(R.string.saves_failed) + " (" + e.javaClass.simpleName + ")"
            }
            ui.post {
                setBusy(false)
                refresh()
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        buttons.forEach { it.isEnabled = !busy }
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    companion object {
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2

        fun manager(a: Activity) = SaveGameManager(File(a.filesDir, "saves"), File(a.filesDir, "saves_backups"))
    }
}
