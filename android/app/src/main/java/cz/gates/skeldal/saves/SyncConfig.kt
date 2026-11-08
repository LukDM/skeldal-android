package cz.gates.skeldal.saves

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import cz.gates.skeldal.install.ImportException
import java.io.File
import java.io.OutputStream

/** Where the sync file is and who this device is (shared by the saves screen and the automatic upload). */
class SyncConfig(private val context: Context) {

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var uri: Uri?
        get() = prefs.getString(KEY_URI, null)?.let { Uri.parse(it) }
        set(v) {
            // another sync file: what this device knew about the old one does not apply
            if (v?.toString() != prefs.getString(KEY_URI, null)) sync().forgetKnown()
            prefs.edit().putString(KEY_URI, v?.toString()).apply()
        }

    /** Upload the saves to the sync file automatically after the game ends. */
    var autoUpload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, false)
        set(v) { prefs.edit().putBoolean(KEY_AUTO_UPLOAD, v).apply() }

    /** Download newer saves from the sync file automatically when the launcher opens. */
    var autoDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)
        set(v) { prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, v).apply() }

    /** Set when a game was started with [autoUpload] on; cleared after the upload. */
    var uploadPending: Boolean
        get() = prefs.getBoolean(KEY_PENDING, false)
        set(v) { prefs.edit().putBoolean(KEY_PENDING, v).apply() }

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    val deviceName: String
        get() = (try { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) } catch (e: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    fun sync() = SaveSync(File(context.filesDir, "saves"), context.cacheDir, deviceId, deviceName,
        File(context.filesDir, "sync_known.txt"))

    fun openWrite(uri: Uri): OutputStream {
        val cr = context.contentResolver
        // "wt" truncates; some cloud providers only know "w" (which replaces the file there)
        return (try { cr.openOutputStream(uri, "wt") } catch (e: Exception) { null }
            ?: cr.openOutputStream(uri, "w"))
            ?: throw ImportException("Do souboru nelze zapisovat.")
    }

    /**
     * Uploads the local saves that are missing in the sync file or newer than the copies there.
     * Local saves are not changed; newer saves in the cloud stay as they are. Returns the number
     * of uploaded saves (0 = nothing to do). Runs on a background thread.
     */
    fun uploadNow(): Int {
        val u = uri ?: return 0
        val s = sync()
        val remote = s.readRemote { context.contentResolver.openInputStream(u) }
        val plan = s.plan(remote).uploadOnly()
        if (plan.uploadCount == 0) {
            remote.dir?.deleteRecursively()
            return 0
        }
        return s.apply(remote, plan, { openWrite(u) }).uploaded
    }

    /** Reads the sync file and returns what is newer there (call [SaveSync.Remote.dir] cleanup or [SaveSync.downloadOnly]). */
    fun check(): Pair<SaveSync.Remote, SaveSync.Plan>? {
        val u = uri ?: return null
        val s = sync()
        val remote = s.readRemote { context.contentResolver.openInputStream(u) }
        return remote to s.plan(remote)
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_AUTO_UPLOAD = "auto_upload"
        private const val KEY_PENDING = "upload_pending"
        private const val KEY_AUTO_DOWNLOAD = "auto_download"
    }
}
