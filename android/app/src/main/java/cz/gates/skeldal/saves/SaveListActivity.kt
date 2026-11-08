package cz.gates.skeldal.saves

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cz.gates.skeldal.R
import cz.gates.skeldal.install.ImportException
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Savegame manager: saves grouped by campaign with name, in-game time and date; rename, export, delete. */
class SaveListActivity : Activity() {

    private lateinit var manager: SaveGameManager
    private lateinit var list: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var pendingExport: List<File> = emptyList()
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = SavesActivity.manager(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF1B1410"))
            addView(list)
        })
        reload()
    }

    private fun savesDir() = File(filesDir, "saves")

    private fun text(s: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun fmt(t: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
    private fun fmtDate(t: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))

    private fun reload() {
        list.removeAllViews()
        list.addView(text(getString(R.string.savelist_title), 22f, "#FFE8D9A8", true))
        val saves = SaveGameManager.saveFiles(savesDir()).map { SaveInfo.read(it) }
        if (saves.isEmpty()) {
            list.addView(text(getString(R.string.saves_none), 16f, "#FFBFB59A"))
            return
        }
        list.addView(text(getString(R.string.savelist_hint), 13f, "#FFBFB59A"))
        val campaigns = saves.groupBy { it.campaign }.entries.sortedByDescending { e -> e.value.maxOf { it.savedAt } }
        for ((campaign, items) in campaigns) {
            val header = text(getString(R.string.savelist_campaign,
                if (campaign > 0) fmtDate(campaign * 1000) else "?", items.size), 16f, "#FFE8D9A8", true).apply {
                setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
                isFocusable = true
                isClickable = true
                setOnClickListener { campaignMenu(campaign, items) }
                foreground = getDrawable(R.drawable.focus_frame)
            }
            list.addView(header)
            for (s in items.sortedByDescending { it.savedAt }) list.addView(row(s))
        }
    }

    private fun row(s: SaveInfo): TextView {
        val title = s.name.ifBlank { getString(R.string.savelist_noname) } +
            if (s.autosave) "  " + getString(R.string.savelist_autosave) else ""
        // (the time in the file name is the game's internal clock, not what the game shows - not displayed)
        val detail = getString(R.string.savelist_detail, fmt(s.savedAt))
        return TextView(this).apply {
            text = "$title\n$detail"
            textSize = 15f
            setTextColor(getColorStateList(R.color.launcher_button_text))
            background = getDrawable(R.drawable.launcher_button)
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            gravity = Gravity.START
            isFocusable = true
            isClickable = true
            setOnClickListener { saveMenu(s) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
            }
        }
    }

    private fun saveMenu(s: SaveInfo) {
        val items = arrayOf(getString(R.string.savelist_rename), getString(R.string.savelist_export), getString(R.string.savelist_delete))
        AlertDialog.Builder(this)
            .setTitle(s.name.ifBlank { getString(R.string.savelist_noname) })
            .setItems(items) { _, i ->
                when (i) {
                    0 -> rename(s)
                    1 -> export(listOf(s.file), s.name)
                    2 -> confirmDelete(listOf(s.file), getString(R.string.savelist_delete_one, s.name))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun campaignMenu(campaign: Long, items: List<SaveInfo>) {
        val opts = arrayOf(getString(R.string.savelist_export_campaign), getString(R.string.savelist_delete_campaign))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.savelist_campaign, if (campaign > 0) fmtDate(campaign * 1000) else "?", items.size))
            .setItems(opts) { _, i ->
                val files = items.map { it.file }
                if (i == 0) export(files, "kampan")
                else confirmDelete(files, getString(R.string.savelist_delete_many, files.size))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rename(s: SaveInfo) {
        val edit = EditText(this).apply {
            setText(s.name)
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(SaveInfo.NAME_SIZE - 1))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.savelist_rename)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    SaveInfo.rename(s.file, edit.text.toString().trim())
                } catch (e: Exception) {
                    message(getString(R.string.saves_failed))
                }
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(files: List<File>, question: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.saves_delete_title)
            .setMessage(question + "\n\n" + getString(R.string.savelist_delete_note))
            .setPositiveButton(R.string.saves_delete_do) { _, _ ->
                manager.backupNow(version())
                val n = files.count { it.delete() }
                // deleted on purpose here: the sync file keeps them (see savelist_delete_note)
                SyncConfig(this).sync().forgetKnown(files.map { it.name })
                reload()
                message(getString(R.string.saves_deleted, n))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun export(files: List<File>, label: String) {
        pendingExport = files
        val safe = label.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().replace(' ', '-').ifEmpty { "pozice" }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "skeldal-$safe.zip"),
            REQ_EXPORT
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK || requestCode != REQ_EXPORT) return
        val files = pendingExport
        Thread {
            val msg = try {
                val out = contentResolver.openOutputStream(uri, "wt") ?: throw ImportException("Soubor nelze vytvořit.")
                out.use { manager.exportFiles(files, it, version()) }
                getString(R.string.saves_exported, files.size)
            } catch (e: Exception) {
                (e as? ImportException)?.message ?: getString(R.string.saves_failed)
            }
            ui.post { message(msg) }
        }.start()
    }

    private fun message(s: String) {
        AlertDialog.Builder(this).setMessage(s).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    companion object {
        private const val REQ_EXPORT = 1
    }
}
