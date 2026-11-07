package cz.gates.skeldal.saves

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
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
    private lateinit var syncInfo: TextView
    private lateinit var syncRun: Button
    private val syncConfig by lazy { SyncConfig(this) }
    private lateinit var syncAuto: Switch
    private lateinit var syncAutoDown: Switch
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
        val delete = findViewById<Button>(R.id.btn_saves_delete)
        val listBtn = findViewById<Button>(R.id.btn_saves_list)
        listBtn.setOnClickListener { startActivity(Intent(this, SaveListActivity::class.java)) }
        delete.setOnClickListener { confirmDelete() }
        syncInfo = findViewById(R.id.sync_info)
        syncRun = findViewById(R.id.btn_sync_run)
        val syncChoose = findViewById<Button>(R.id.btn_sync_choose)
        syncAuto = findViewById(R.id.switch_sync_auto)
        syncAuto.isChecked = syncConfig.autoUpload
        syncAuto.setOnCheckedChangeListener { _, on -> syncConfig.autoUpload = on }
        syncAutoDown = findViewById(R.id.switch_sync_auto_down)
        syncAutoDown.isChecked = syncConfig.autoDownload
        syncAutoDown.setOnCheckedChangeListener { _, on -> syncConfig.autoDownload = on }
        buttons = listOf(listBtn, export, import, restore, delete, close, syncRun, syncChoose)
        syncRun.setOnClickListener { syncPrepare() }
        syncChoose.setOnClickListener { chooseSyncFile() }

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
        listBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        syncStatus()
    }

    private fun refresh() {
        val s = manager.summary()
        info.text = if (s.count == 0) getString(R.string.saves_none)
        else getString(R.string.saves_summary, s.count,
            DateFormat.getDateTimeInstance().format(Date(s.newest)), (s.totalBytes + 1023) / 1024)
        findViewById<Button>(R.id.btn_saves_export).isEnabled = s.count > 0
        findViewById<Button>(R.id.btn_saves_restore).isEnabled = manager.backups().isNotEmpty()
        findViewById<Button>(R.id.btn_saves_delete).isEnabled = s.count > 0
        findViewById<Button>(R.id.btn_saves_list).isEnabled = s.count > 0
    }

    private fun confirmDelete() {
        val n = manager.summary().count
        if (n == 0) return
        if (gameRunning()) {
            AlertDialog.Builder(this).setMessage(R.string.sync_game_running).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.saves_delete_title)
            .setMessage(getString(R.string.saves_delete_confirm, n))
            .setPositiveButton(R.string.saves_delete_do) { _, _ ->
                background {
                    val (deleted, _) = manager.deleteAll(version())
                    ui.post { syncStatus() }
                    getString(R.string.saves_deleted, deleted)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_SYNC_NEW, REQ_SYNC_OPEN -> {
                try {
                    contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // provider without persistable permissions: works until the app is restarted
                }
                syncConfig.uri = uri
                syncStatus()
            }
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

    // ---- synchronisation (see SaveSync) ---------------------------------------------------

    private fun syncUri(): Uri? = syncConfig.uri

    private fun deviceId(): String = syncConfig.deviceId

    private fun sync() = syncConfig.sync()

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null } ?: uri.lastPathSegment ?: "?"

    private fun fmt(t: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

    private fun gameRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any { it.processName.endsWith(":game") } == true
    }

    private fun chooseSyncFile() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_choose_title)
            .setItems(arrayOf(getString(R.string.sync_choose_new), getString(R.string.sync_choose_existing))) { _, i ->
                val intent = if (i == 0)
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "skeldal-sync.zip")
                else
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                startActivityForResult(intent, if (i == 0) REQ_SYNC_NEW else REQ_SYNC_OPEN)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows what is in the sync file: devices, newest save, what a sync would change. */
    private fun syncStatus() {
        val uri = syncUri()
        syncRun.isEnabled = uri != null
        syncAuto.isEnabled = uri != null
        syncAutoDown.isEnabled = uri != null
        if (uri == null) { syncInfo.text = getString(R.string.sync_not_set); return }
        val name = displayName(uri)
        syncInfo.text = getString(R.string.sync_loading, name)
        Thread {
            val text = try {
                val s = sync()
                val remote = s.readRemote { contentResolver.openInputStream(uri) }
                val plan = s.plan(remote)
                remote.dir?.deleteRecursively()
                if (remote.isEmpty && remote.devices.isEmpty()) getString(R.string.sync_status_empty, name)
                else {
                    val me = deviceId()
                    val devices = remote.devices.sortedByDescending { it.lastSync }.joinToString("\n") {
                        getString(R.string.sync_device_line, it.name, if (it.id == me) getString(R.string.sync_this_device) else "",
                            fmt(it.lastSync), it.saves)
                    }.ifEmpty { getString(R.string.sync_no_devices) }
                    getString(R.string.sync_status, name, remote.entries.size, fmt(remote.newest()), devices,
                        plan.download.size, plan.upload.size, plan.unchanged)
                }
            } catch (e: ImportException) {
                getString(R.string.sync_read_failed, name, e.message ?: "")
            } catch (e: Exception) {
                getString(R.string.sync_read_failed, name, e.javaClass.simpleName)
            }
            ui.post { if (!isFinishing) syncInfo.text = text }
        }.start()
    }

    /** Reads the sync file, shows what will happen and synchronises after confirmation. */
    private fun syncPrepare() {
        val uri = syncUri() ?: return
        if (gameRunning()) {
            AlertDialog.Builder(this).setMessage(R.string.sync_game_running).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        setBusy(true)
        Thread {
            try {
                val s = sync()
                val remote = s.readRemote { contentResolver.openInputStream(uri) }
                val plan = s.plan(remote)
                ui.post {
                    setBusy(false)
                    if (!plan.hasChanges) {
                        remote.dir?.deleteRecursively()
                        AlertDialog.Builder(this).setMessage(R.string.sync_nothing).setPositiveButton(android.R.string.ok, null).show()
                        return@post
                    }
                    val down = plan.download.size
                    val up = plan.upload.size
                    val options = ArrayList<Pair<String, () -> String>>()
                    val write = { p: SaveSync.Plan ->
                        s.apply(remote, p, { syncConfig.openWrite(uri) })
                    }
                    if (down > 0) options.add(getString(R.string.sync_opt_download, down) to {
                        manager.backupNow(version())
                        val n = s.downloadOnly(remote, plan)
                        getString(R.string.sync_done_download, n)
                    })
                    if (up > 0) options.add(getString(R.string.sync_opt_upload, up) to {
                        val r = write(plan.copy(download = emptyList()))
                        getString(R.string.sync_done_upload, r.uploaded)
                    })
                    if (down > 0 && up > 0) options.add(getString(R.string.sync_opt_both, down, up) to {
                        manager.backupNow(version())
                        val r = write(plan)
                        getString(R.string.sync_done_both, r.downloaded, r.uploaded, r.total)
                    })
                    AlertDialog.Builder(this)
                        .setTitle(R.string.sync_dialog_title)
                        .setItems(options.map { it.first }.toTypedArray()) { _, k ->
                            background {
                                val msg = options[k].second()
                                ui.post { syncStatus() }
                                msg
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> remote.dir?.deleteRecursively() }
                        .show()
                }
            } catch (e: Exception) {
                val msg = (e as? ImportException)?.message ?: (getString(R.string.saves_failed) + " (" + e.javaClass.simpleName + ")")
                ui.post {
                    setBusy(false)
                    AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }.start()
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
        private const val REQ_SYNC_NEW = 3
        private const val REQ_SYNC_OPEN = 4

        fun manager(a: Activity) = SaveGameManager(File(a.filesDir, "saves"), File(a.filesDir, "saves_backups"))
    }
}
