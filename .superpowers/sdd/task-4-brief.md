### Task 4: Settings UI — debug switch + log export

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (after line 151), `values-es/strings.xml` (after line 152), `values-zh/strings.xml` (after line 152)
- Modify: `app/src/main/res/layout/activity_settings_misc.xml` (General card, after the `misc_hide_recents_desc` TextView; import-block pattern reference: the `btnExportSettings` row)
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/MiscellaneousSettingsActivity.kt`

**Interfaces:**
- Consumes: `PanelPreferences.debugLogEnabled`, `DebugLog.session/shutdown/readAll` (Task 1).
- Produces: nothing consumed later.

- [ ] **Step 1: Add strings in all three locales**

`values/strings.xml` (after `misc_hide_recents_desc`):

```xml
    <string name="misc_debug_log_label">Debug Logging</string>
    <string name="misc_debug_log_desc">Records detailed diagnostic logs on this device to help diagnose rare issues; use Export Logs to share them. Logs stay on your device until you export</string>
    <string name="misc_export_log_title">Export Logs</string>
    <string name="misc_export_log_desc">Save the debug log as a .txt file in Downloads/SidePanel</string>
```

`values-zh/strings.xml`:

```xml
    <string name="misc_debug_log_label">调试日志</string>
    <string name="misc_debug_log_desc">在本机记录详细诊断日志,用于排查偶发问题;可通过导出日志分享分析。日志仅保存在本机,导出由你主动触发</string>
    <string name="misc_export_log_title">导出日志</string>
    <string name="misc_export_log_desc">将调试日志保存为 txt 文件到 Downloads/SidePanel</string>
```

`values-es/strings.xml`:

```xml
    <string name="misc_debug_log_label">Registro de depuración</string>
    <string name="misc_debug_log_desc">Guarda registros de diagnóstico detallados en el dispositivo para diagnosticar problemas poco frecuentes; usa Exportar para compartirlos. Los registros permanecen en tu dispositivo hasta que los exportes</string>
    <string name="misc_export_log_title">Exportar registros</string>
    <string name="misc_export_log_desc">Guarda el registro de depuración como .txt en Descargas/SidePanel</string>
```

- [ ] **Step 2: Add switch + export row to the layout**

In `activity_settings_misc.xml`, inside the same General card, directly after the `misc_hide_recents_desc` TextView, insert:

```xml
                    <!-- Debug logging -->
                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/feature_debug_log"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/misc_debug_log_label"
                        android:textColor="?attr/colorOnSurface"
                        android:textSize="16sp"
                        android:paddingVertical="12dp" />

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/misc_debug_log_desc"
                        android:textColor="?attr/colorOnSurfaceVariant"
                        android:textSize="14sp"
                        android:layout_marginBottom="8dp" />

                    <!-- Export logs row (mirrors btnExportSettings structure) -->
                    <LinearLayout
                        android:id="@+id/btn_export_log"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:clickable="true"
                        android:focusable="true"
                        android:background="?attr/selectableItemBackground"
                        android:paddingVertical="12dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/misc_export_log_title"
                            android:textColor="?attr/colorOnSurface"
                            android:textSize="16sp" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/misc_export_log_desc"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="14sp" />
                    </LinearLayout>

                    <View
                        android:layout_width="match_parent"
                        android:layout_height="1dp"
                        android:background="?attr/colorOutlineVariant"
                        android:layout_marginVertical="4dp" />
```

- [ ] **Step 3: Extract a shared Downloads writer and wire the UI**

In `MiscellaneousSettingsActivity`:

(a) Add a private helper (refactor target for both export paths):

```kotlin
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
```

(b) Rewrite `exportSettingsToDownloads()` to use it (same toasts, off the main thread since JSON can be sizeable):

```kotlin
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
```

(c) In `onCreate`, after the `featureHideRecents` block, add:

```kotlin
        binding.featureDebugLog.isChecked = panelPrefs.debugLogEnabled
        binding.featureDebugLog.setOnCheckedChangeListener { _, isChecked ->
            panelPrefs.debugLogEnabled = isChecked
            if (isChecked) DebugLog.session(this) else DebugLog.shutdown()
        }

        binding.btnExportLog.setOnClickListener { exportDebugLog() }
```

(d) Add the export function (log content can be ~4MB — background thread, then toast on main):

```kotlin
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
```

- [ ] **Step 4: Build and lint to verify**

Run: `./gradlew assembleDebug` then `./gradlew lint` (mirrors workaround).
Expected: build `BUILD SUCCESSFUL`; lint fails only with the 55 known pre-existing errors — verify none of the error lines mention the new string names, `feature_debug_log`, or `btn_export_log` (missing translations would appear as new MissingTranslation errors).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/res/layout/activity_settings_misc.xml \
        app/src/main/java/com/imi/smartedge/sidebar/panel/MiscellaneousSettingsActivity.kt
git commit -m "feat(log): add debug logging switch and log export in misc settings"
```

---

