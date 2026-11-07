package cz.gates.skeldal

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import cz.gates.skeldal.diag.CrashMonitor
import cz.gates.skeldal.install.GameImporter
import cz.gates.skeldal.install.GameInstallationDetector
import cz.gates.skeldal.install.ImportException
import cz.gates.skeldal.install.ImportSource
import cz.gates.skeldal.install.InstallationRepository
import cz.gates.skeldal.install.SafTreeSource
import cz.gates.skeldal.install.ZipFileSource
import cz.gates.skeldal.saves.SyncConfig
import cz.gates.skeldal.settings.GameConfigWriter
import cz.gates.skeldal.settings.SettingsActivity
import cz.gates.skeldal.settings.SettingsStore
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Launcher: import and manage the game data, start the game. */
class MainActivity : Activity() {

    enum class LauncherState { NO_INSTALLATION, IMPORTING, VALIDATING, READY, INVALID, INCOMPLETE }

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnImportFolder: Button
    private lateinit var btnImportZip: Button
    private lateinit var btnFiles: Button
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var repo: InstallationRepository
    private val crashMonitor by lazy { CrashMonitor(filesDir) }

    /** Non-null while an import runs. */
    private var busyState: LauncherState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = GameDataLocator.repository(this)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnStart = findViewById(R.id.btn_start)
        btnImportFolder = findViewById(R.id.btn_import)
        btnImportZip = findViewById(R.id.btn_import_zip)
        btnFiles = findViewById(R.id.btn_files)

        btnStart.setOnClickListener { onStartClicked() }
        btnImportFolder.setOnClickListener { pickFolder() }
        btnImportZip.setOnClickListener { pickZip() }
        btnFiles.setOnClickListener { showManageDialog() }
        findViewById<Button>(R.id.btn_saves).setOnClickListener {
            startActivity(Intent(this, cz.gates.skeldal.saves.SavesActivity::class.java))
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_info).setOnClickListener { showInfo() }
        findViewById<Button>(R.id.btn_exit).setOnClickListener { finishAffinity() }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        if (busyState == null) reportLastRun()
        autoUploadAttempts = 0
        ui.removeCallbacks(autoUploadCheck)
        ui.post(autoUploadCheck)
        // gamepad / D-pad navigation starts on the most useful button
        (if (btnStart.isEnabled) btnStart else btnImportFolder).requestFocus()
    }

    // ---- state ---------------------------------------------------------------------------

    private fun currentState(): LauncherState {
        busyState?.let { return it }
        val inst = repo.load()
        if (!repo.hasGameFiles()) {
            return if (inst != null) LauncherState.INVALID else LauncherState.NO_INSTALLATION
        }
        return when (inst?.status) {
            null, GameInstallationDetector.Status.VALID -> LauncherState.READY
            GameInstallationDetector.Status.INCOMPLETE -> LauncherState.INCOMPLETE
            GameInstallationDetector.Status.INVALID -> LauncherState.INVALID
        }
    }

    private fun refreshState() {
        val state = currentState()
        val busy = state == LauncherState.IMPORTING || state == LauncherState.VALIDATING
        btnStart.isEnabled = state == LauncherState.READY || state == LauncherState.INCOMPLETE
        btnImportFolder.isEnabled = !busy
        btnImportZip.isEnabled = !busy
        btnFiles.isEnabled = !busy && (repo.hasGameFiles() || repo.load() != null)
        if (!busy) progress.visibility = View.GONE

        val inst = repo.load()
        status.text = when (state) {
            LauncherState.NO_INSTALLATION -> getString(R.string.state_no_installation)
            LauncherState.IMPORTING -> getString(R.string.state_importing)
            LauncherState.VALIDATING -> getString(R.string.state_validating)
            LauncherState.READY -> getString(R.string.state_ready, describe(inst))
            LauncherState.INCOMPLETE -> getString(R.string.state_incomplete, describe(inst))
            LauncherState.INVALID -> getString(R.string.state_invalid)
        }
    }

    private fun describe(inst: InstallationRepository.Installation?): String =
        if (inst == null) "" else "${inst.type.label} · ${inst.totalBytes / GameImporter.MB} MB"

    // ---- start ---------------------------------------------------------------------------

    private fun onStartClicked() {
        if (currentState() == LauncherState.INCOMPLETE) {
            val missing = repo.load()?.missingExpected?.joinToString("\n") ?: ""
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.incomplete_start_warning, missing))
                .setPositiveButton(R.string.btn_start_anyway) { _, _ -> launchGame() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            launchGame()
        }
    }

    private fun launchGame() {
        crashMonitor.markStarted()
        try {
            GameConfigWriter.write(
                File(filesDir, "skeldal.ini"), SettingsStore(this).load(),
                repo.gameDir, File(filesDir, "saves")
            )
        } catch (e: Exception) {
            message(getString(R.string.error_config))
            return
        }
        val sync = SyncConfig(this)
        if (sync.autoUpload && sync.uri != null) sync.uploadPending = true
        startActivity(Intent(this, GameActivity::class.java))
    }

    // ---- automatic upload of the saves after the game (SyncConfig.autoUpload) --------------

    private var autoUploadAttempts = 0
    private var autoUploadRunning = false

    /** Waits until the game process has ended (it may take a moment after returning), then uploads. */
    private val autoUploadCheck: Runnable = object : Runnable {
        override fun run() {
            val sync = SyncConfig(this@MainActivity)
            if (!sync.uploadPending || autoUploadRunning || isFinishing) return
            if (!sync.autoUpload || sync.uri == null) { sync.uploadPending = false; return }
            if (gameProcessAlive()) {
                // the game is still running (e.g. only switched to the launcher): try again a bit later
                if (++autoUploadAttempts < 10) ui.postDelayed(this, 1000)
                return
            }
            autoUploadRunning = true
            val app = applicationContext
            Thread {
                val msg = try {
                    val n = sync.uploadNow()
                    sync.uploadPending = false
                    if (n > 0) getString(R.string.sync_auto_done, n) else null
                } catch (e: Exception) {
                    // stays pending: the next start of the launcher tries again
                    getString(R.string.sync_auto_failed, (e as? ImportException)?.message ?: e.javaClass.simpleName)
                }
                ui.post {
                    autoUploadRunning = false
                    if (msg != null) Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    // ---- import --------------------------------------------------------------------------

    private fun pickFolder() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            REQ_PICK_FOLDER
        )
    }

    private fun pickZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, REQ_PICK_ZIP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_FOLDER -> {
                val name = DocumentFile.fromTreeUri(this, uri)?.name ?: "složka"
                startImport(name) { SafTreeSource(applicationContext, uri) }
            }
            REQ_PICK_ZIP -> {
                val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "archiv.zip"
                startImport(name) { zipSource(uri) }
            }
        }
    }

    private fun startImport(sourceName: String, makeSource: () -> ImportSource) {
        busyState = LauncherState.IMPORTING
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        refreshState()

        val importer = GameImporter(repo, filesDir) { StatFs(filesDir.path).availableBytes }
        Thread {
            var lastKey = ""
            var source: ImportSource? = null
            val error: String? = try {
                source = makeSource()
                importer.import(source, sourceName) { p ->
                    // post to the UI only when something visible changes (not for every 64 kB block)
                    val percent = if (p.totalBytes > 0) (p.copiedBytes * 100 / p.totalBytes).toInt() else 0
                    val key = "${p.phase}:$percent"
                    if (key != lastKey) {
                        lastKey = key
                        ui.post { showProgress(p.phase, percent) }
                    }
                }
                null
            } catch (e: ImportException) {
                android.util.Log.e("skeldal", "import failed", e)
                // technical cause in small print, helps when reporting a problem
                e.message + (e.cause?.let { "\n\n(${it.javaClass.simpleName}: ${it.message})" } ?: "")
            } catch (e: SecurityException) {
                android.util.Log.e("skeldal", "import failed", e)
                getString(R.string.error_access_denied)
            } catch (e: Exception) {
                android.util.Log.e("skeldal", "import failed", e)
                getString(R.string.error_import_generic, e.javaClass.simpleName + ": " + e.message)
            } finally {
                (source as? AutoCloseable)?.close()
            }
            ui.post { finishImport(error) }
        }.start()
    }

    /**
     * A ZIP must be readable with random access for its central directory. Files on the device
     * are opened in place (through the file descriptor); others (e.g. cloud storage) are copied
     * to the cache first. Runs on the import thread.
     */
    private fun zipSource(uri: Uri): ImportSource {
        val pfd = try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
        if (pfd != null) {
            val seekable = try {
                android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                true
            } catch (e: Exception) { false }
            if (seekable) {
                val src = ZipFileSource(File("/proc/self/fd/${pfd.fd}")) { pfd.close() }
                try {
                    src.list()          // probe; list() is called again by the importer
                    return src
                } catch (e: ImportException) {
                    if (e.cause !is java.io.IOException && e.cause !is SecurityException) { src.close(); throw e }
                    src.close()        // cannot reopen through /proc -> fall back to a copy
                } catch (e: Exception) {
                    src.close()
                }
            } else {
                pfd.close()
            }
        }
        val tmp = File(cacheDir, "import.zip")
        tmp.delete()
        val input = contentResolver.openInputStream(uri) ?: throw ImportException("Archiv nelze otevřít.")
        val size = DocumentFile.fromSingleUri(this, uri)?.length() ?: 0L
        if (size > 0 && StatFs(cacheDir.path).availableBytes < size * 2 + GameImporter.SAFETY_MARGIN) {
            input.close()
            throw ImportException("Není dost volného místa pro rozbalení archivu (potřeba cca ${size * 2 / GameImporter.MB} MB).")
        }
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        return ZipFileSource(tmp) { tmp.delete() }
    }

    private fun showProgress(phase: GameImporter.Phase, percent: Int) {
        if (busyState == null) return // import already finished
        when (phase) {
            GameImporter.Phase.LISTING, GameImporter.Phase.VALIDATING, GameImporter.Phase.VERIFYING -> {
                busyState = LauncherState.VALIDATING
                progress.isIndeterminate = true
                status.setText(R.string.state_validating)
            }
            GameImporter.Phase.COPYING -> {
                busyState = LauncherState.IMPORTING
                progress.isIndeterminate = false
                progress.progress = percent
                status.text = getString(R.string.state_importing_percent, percent)
            }
        }
    }

    private fun finishImport(error: String?) {
        busyState = null
        progress.visibility = View.GONE
        if (error == null) {
            Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
            if (repo.load()?.status == GameInstallationDetector.Status.INCOMPLETE) showManageDialog()
        } else {
            message(error)
        }
        refreshState()
    }

    // ---- management ----------------------------------------------------------------------

    private fun showManageDialog() {
        val inst = repo.load()
        val text = buildString {
            if (inst == null) {
                append(getString(R.string.manage_no_metadata))
            } else {
                append(getString(R.string.manage_type, inst.type.label)).append('\n')
                append(getString(R.string.manage_source, inst.sourceName)).append('\n')
                append(getString(R.string.manage_date,
                    DateFormat.getDateTimeInstance().format(Date(inst.importedAt)))).append('\n')
                append(getString(R.string.manage_files, inst.fileCount, inst.totalBytes / GameImporter.MB)).append('\n')
                append(getString(R.string.manage_music, inst.musicTracks)).append('\n')
                append(getString(if (inst.hasIntroVideo) R.string.manage_video_yes else R.string.manage_video_no)).append('\n')
                append(getString(R.string.manage_languages,
                    if (inst.languages.isEmpty()) "cz" else "cz, " + inst.languages.joinToString(", ")))
                if (inst.missingExpected.isNotEmpty()) {
                    append("\n\n").append(getString(R.string.manage_missing, inst.missingExpected.joinToString("\n")))
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_manage_files)
            .setMessage(text)
            .setPositiveButton(R.string.btn_verify) { _, _ -> verifyInstalled() }
            .setNegativeButton(R.string.btn_delete_data) { _, _ -> confirmDelete() }
            .setNeutralButton(R.string.btn_close, null)
            .show()
    }

    private fun verifyInstalled() {
        busyState = LauncherState.VALIDATING
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        refreshState()
        Thread {
            val result = try {
                GameImporter(repo, filesDir) { StatFs(filesDir.path).availableBytes }.verifyInstalled()
            } catch (e: Exception) {
                null
            }
            ui.post {
                busyState = null
                refreshState()
                val msg = when (result?.status) {
                    null -> getString(R.string.verify_no_data)
                    GameInstallationDetector.Status.VALID -> getString(R.string.verify_ok, result.files.size)
                    GameInstallationDetector.Status.INCOMPLETE ->
                        getString(R.string.manage_missing, result.missingExpected.joinToString("\n"))
                    GameInstallationDetector.Status.INVALID -> getString(R.string.state_invalid)
                }
                message(msg)
            }
        }.start()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_delete_data)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.btn_delete_data) { _, _ ->
                repo.delete()
                refreshState()
                Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- misc ----------------------------------------------------------------------------

    // ---- last game session ----------------------------------------------------------------

    private fun gameProcessAlive(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any { it.processName == "$packageName:game" } == true
    }

    private fun reportLastRun() {
        when (val r = crashMonitor.consume(gameProcessAlive())) {
            CrashMonitor.LastRun.Ok -> {}
            CrashMonitor.LastRun.Crashed -> AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.crash_message)
                .setPositiveButton(R.string.crash_restart) { _, _ -> onStartClicked() }
                .setNeutralButton(R.string.crash_open_log) { _, _ -> showLog() }
                .setNegativeButton(R.string.btn_verify) { _, _ -> verifyInstalled() }
                .show()
            is CrashMonitor.LastRun.Error -> {
                val f = CrashMonitor.friendlyMessage(r.message)
                val b = AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(getString(R.string.error_with_details, f.text, r.message))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.crash_open_log) { _, _ -> showLog() }
                if (f.offerCheck) b.setNegativeButton(R.string.btn_verify) { _, _ -> verifyInstalled() }
                b.show()
            }
        }
    }

    private fun showLog() {
        val text = crashMonitor.logTail(300).ifEmpty { getString(R.string.log_empty) }
        val view = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.log_title)
            .setView(android.widget.ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showInfo() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.info_text_full, version, engineLine()))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.btn_licenses) { _, _ -> showLicenses() }
            .show()
    }

    /** "Engine: …" line; omitted when the version is unknown (e.g. built without git). */
    private fun engineLine(): String {
        val v = BuildConfig.ENGINE_VERSION
        return if (v.isNotEmpty()) "\n" + getString(R.string.info_engine, v, BuildConfig.SDL_VERSION)
               else "\n" + getString(R.string.info_sdl, BuildConfig.SDL_VERSION)
    }

    private fun showLicenses() {
        val text = resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
        val view = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextIsSelectable(true)
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_licenses)
            .setView(android.widget.ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun message(text: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val REQ_PICK_FOLDER = 1
        private const val REQ_PICK_ZIP = 2
    }
}

object GameDataLocator {
    fun repository(context: Context): InstallationRepository = InstallationRepository(context.filesDir)

    /** Private directory with the imported game files; must match platform/android/app_start.cpp. */
    fun dataDirectory(context: Context): File = repository(context).gameDir
}
