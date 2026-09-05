package com.imi.smartedge.sidebar.panel

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsMiscBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MiscellaneousSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsMiscBinding
    private lateinit var panelPrefs: PanelPreferences

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private val importFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val stream = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
            val json = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            stream.close()
            val success = panelPrefs.importFromJson(json)
            if (success) {
                applyGlobalRefresh()
                binding.root.showModernToast("Settings imported successfully!")
            } else {
                binding.root.showModernToast("Invalid backup file – import failed")
            }
        } catch (e: Exception) {
            binding.root.showModernToast("Could not read file: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsMiscBinding.inflate(layoutInflater)
        setContentView(binding.root)

        panelPrefs = PanelPreferences(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        updateLanguageLabel()
        binding.featureLanguage.setOnClickListener { showLanguagePicker() }

        binding.featureHideNotification.isChecked = panelPrefs.hideServiceNotification
        binding.featureHideNotification.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.hideServiceNotification = isChecked
            // ACTION_REFRESH applies/strips the foreground notification immediately
            startService(Intent(this, FloatingPanelService::class.java).apply {
                action = FloatingPanelService.ACTION_REFRESH
            })
        }

        // Kotlin local vals are out of scope inside their own initializer, so the
        // self-referencing rollback listener needs a two-step var assignment.
        var hideRecentsListener: android.widget.CompoundButton.OnCheckedChangeListener? = null
        hideRecentsListener = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            val previous = panelPrefs.hideFromRecents
            try {
                RecentsHideHelper.apply(this, isChecked)
                panelPrefs.hideFromRecents = isChecked
            } catch (e: Exception) {
                // Roll the preference and the switch back on ROMs where component
                // switching fails; re-checking fires the listener again, so detach it first.
                panelPrefs.hideFromRecents = previous
                binding.featureHideRecents.setOnCheckedChangeListener(null)
                binding.featureHideRecents.isChecked = previous
                binding.featureHideRecents.setOnCheckedChangeListener(hideRecentsListener)
                // The first PackageManager call may have landed before the
                // throw, leaving alias state flipped against the rolled-back
                // preference. Reconcile immediately instead of waiting for
                // the next process start; sync() can itself fail, so guard it.
                runCatching { RecentsHideHelper.sync(this) }
                    .onFailure {
                        Log.w("MiscellaneousSettingsActivity", "Post-rollback alias sync failed", it)
                    }
                binding.root.showModernToast("Couldn't change Recents visibility: ${e.message}")
            }
        }
        binding.featureHideRecents.isChecked = panelPrefs.hideFromRecents
        binding.featureHideRecents.setOnCheckedChangeListener(hideRecentsListener)

        binding.featureDebugLog.isChecked = panelPrefs.debugLogEnabled
        binding.featureDebugLog.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.debugLogEnabled = isChecked
            if (isChecked) DebugLog.session(this) else DebugLog.shutdown()
        }

        binding.btnExportLog.setOnClickListener { exportDebugLog() }

        binding.btnExportSettings.setOnClickListener {
            exportSettingsToDownloads()
        }

        binding.btnImportSettings.setOnClickListener {
            importFilePicker.launch("application/json")
        }
    }

    private fun updateLanguageLabel() {
        binding.tvLanguageValue.text = when (panelPrefs.appLanguage) {
            "es" -> "Español"
            "zh" -> "中文(简体)"
            else -> "English"
        }
    }

    private fun showLanguagePicker() {
        val languages = arrayOf("English", "Español", "中文(简体)")
        val codes = arrayOf("en", "es", "zh")
        val currentIndex = codes.indexOf(panelPrefs.appLanguage).let { if (it == -1) 0 else it }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.misc_app_language))
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selected = codes[which]
                if (selected != panelPrefs.appLanguage) {
                    panelPrefs.appLanguage = selected
                    LocaleHelper.setLocale(this, selected)
                    
                    // Restart app
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    /** Writes text to Downloads/SidePanel/<fileName>; returns the display path or null on failure. */
    private fun saveToDownloads(fileName: String, mimeType: String, content: String): String? {
        val folderName = "SidePanel"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage — write via MediaStore to Downloads/SidePanel/
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    ?: return null
                "Downloads/$folderName/$fileName"
            } else {
                // Legacy — write directly to Downloads/SidePanel/
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    folderName
                )
                dir.mkdirs()
                java.io.File(dir, fileName).writeText(content)
                "Downloads/$folderName/$fileName"
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun exportSettingsToDownloads() {
        Thread {
            val json = panelPrefs.exportToJson()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val saved = saveToDownloads("smartedge_backup_$timestamp.json", "application/json", json)
            runOnUiThread {
                if (saved != null) binding.root.showModernToast("Saved to $saved")
                else binding.root.showModernToast("Export failed – could not create file")
            }
        }.start()
    }

    private fun exportDebugLog() {
        Thread {
            val content = DebugLog.readAll(this)
            if (content == null) {
                runOnUiThread {
                    binding.root.showModernToast("No logs yet - turn on Debug Logging first")
                }
                return@Thread
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val saved = saveToDownloads("smartedge_log_$timestamp.txt", "text/plain", content)
            runOnUiThread {
                if (saved != null) binding.root.showModernToast("Saved to $saved")
                else binding.root.showModernToast("Export failed – could not create file")
            }
        }.start()
    }

    private fun applyGlobalRefresh() {
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(intent)
    }
}
