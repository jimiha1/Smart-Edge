# IME 检测修复 + 调试文件日志 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix IME visibility detection so third-party keyboards (WeChat/Doubao/Baidu) hide the edge handle, and add a default-off file logger with targeted instrumentation of the handle show/hide decision chain for diagnosing rare onlyOnHome mis-triggers.

**Architecture:** A new dependency-free `DebugLog` object writes timestamped lines to two rotating 2MB files on a single background thread (every line flushed). The accessibility service's IME detection switches its primary signal to "event package ∈ enabled-IME set" (parsed from `Settings.Secure.ENABLED_INPUT_METHODS`, 30s cache) with the old className check kept as fallback. Instrumentation logs the full gate snapshot at every show/hide decision plus the `isCurrentPackageLauncher` match branch. A settings switch + export button (reusing the existing MediaStore Downloads pattern) round it out.

**Tech Stack:** Plain Kotlin, Android Views + ViewBinding, no new dependencies. Spec: `docs/superpowers/specs/2026-09-05-ime-detection-fix-and-debug-logging-design.md`.

## Global Constraints

- JDK 17, commands from repo root in Git Bash: `./gradlew assembleDebug`.
- KNOWN WORKAROUND: `~/.gradle/init.d/mirrors.gradle` conflicts with this project's `FAIL_ON_PROJECT_REPOS` and aborts gradle — move it to `~/.gradle/init.d/mirrors.gradle.bak` before any gradle run, restore after.
- `./gradlew lint` has 55 pre-existing errors on main (verified by stash A/B in a prior feature) — this feature must add ZERO new lint issues; missing translations in all three locales would be new errors.
- No unit-test source sets exist in this repo — per-task verification is the build; behavioral verification is Task 5 (emulator + real device). Do not add test infrastructure.
- All SharedPreferences access via `PanelPreferences` property accessors. `debugLogEnabled` is NOT added to the backup export whitelist.
- User-facing strings in all three locales (`values/`, `values-es/`, `values-zh/`); zh uses half-width punctuation, no trailing period.
- No new dependencies (F-Droid). Existing `Log.x` calls stay untouched.
- Commit style: conventional prefixes (`feat(scope)`, `fix(scope)`, `chore`).
- Implement on branch `feat/ime-fix-debug-log` (create from main before Task 1).

---

### Task 1: DebugLog component + preference

**Files:**
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/PanelPreferences.kt` (companion constant near line 87, property near line 696)
- Create: `app/src/main/java/com/imi/smartedge/sidebar/panel/DebugLog.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (used by Tasks 2–4): `DebugLog.i(tag: String, msg: String)`, `DebugLog.e(tag: String, msg: String, tr: Throwable? = null)`, `DebugLog.session(context: Context)`, `DebugLog.shutdown()`, `DebugLog.readAll(context: Context): String?`, `PanelPreferences.debugLogEnabled: Boolean` (default `false`).

- [ ] **Step 1: Create the feature branch**

```bash
git checkout -b feat/ime-fix-debug-log
```

- [ ] **Step 2: Add the preference**

In `PanelPreferences` companion object, next to `KEY_ONLY_ON_HOME` (line ~87):

```kotlin
        private const val KEY_ONLY_ON_HOME = "only_on_home"
        private const val KEY_DEBUG_LOG = "debug_log_enabled"
```

Immediately after the existing `onlyOnHome` property (line ~698):

```kotlin
    /** Device-level debug logging switch (see DebugLog); not part of backup export. */
    var debugLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit { putBoolean(KEY_DEBUG_LOG, value) }
```

- [ ] **Step 3: Create DebugLog.kt**

New file `app/src/main/java/com/imi/smartedge/sidebar/panel/DebugLog.kt`:

```kotlin
package com.imi.smartedge.sidebar.panel

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Size-capped rotating file logger for on-device debugging of rare repro
 * issues (e.g. the onlyOnHome handle showing inside apps). All writes run
 * on one background thread so lines stay ordered; every line is flushed
 * immediately so a process kill loses at most the in-flight line.
 *
 * Enable/disable comes from PanelPreferences.debugLogEnabled. Until
 * session() has initialized the writer, i()/e() are zero-cost no-ops.
 * Files live in filesDir/logs: smartedge.log (newest) and
 * smartedge.log.1 (previous) — about 4 MB of history in total.
 */
object DebugLog {

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    @Volatile private var enabled = false
    private var appContext: Context? = null
    private var handler: Handler? = null
    private var writer: FileWriter? = null
    private var writerFile: File? = null
    private val lineTs = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Initializes the log thread and writes a session header, but only when
     * the preference is on. Called from the settings toggle and from
     * FloatingPanelService.onCreate so logging survives process restarts.
     */
    fun session(context: Context) {
        if (!PanelPreferences(context).debugLogEnabled) {
            shutdown()
            return
        }
        synchronized(this) {
            if (handler == null) {
                val thread = HandlerThread("DebugLog", Process.THREAD_PRIORITY_BACKGROUND)
                thread.start()
                handler = Handler(thread.looper)
            }
            appContext = context.applicationContext
            enabled = true
        }
        header()
    }

    fun i(tag: String, msg: String) = enqueue(tag, msg, null)

    fun e(tag: String, msg: String, tr: Throwable? = null) = enqueue(tag, msg, tr)

    /** Stops accepting lines and closes the writer (settings toggle off). */
    fun shutdown() {
        synchronized(this) { enabled = false }
        handler?.post {
            runCatching { writer?.close() }
            writer = null
            writerFile = null
        }
    }

    /** Combined log content, oldest first; null when no log file exists yet. */
    fun readAll(context: Context): String? {
        val dir = File(context.filesDir, "logs")
        val prev = File(dir, "smartedge.log.1")
        val cur = File(dir, "smartedge.log")
        if (!prev.exists() && !cur.exists()) return null
        return (if (prev.exists()) prev.readText() else "") +
            (if (cur.exists()) cur.readText() else "")
    }

    private fun header() {
        val ctx = appContext ?: return
        val prefs = PanelPreferences(ctx)
        val version = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName}(${pi.versionCode})"
        }.getOrDefault("?")
        DebugLog.i(
            "DebugLog",
            "==== session ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
                "pid=${Process.myPid()} device=${android.os.Build.MODEL} " +
                "api=${android.os.Build.VERSION.SDK_INT} app=$version\n" +
                "  prefs: onlyOnHome=${prefs.onlyOnHome} panelSide=${prefs.panelSide} " +
                "gestures=${prefs.gesturesEnabled} tap=${prefs.tapToOpen} " +
                "dTap=${prefs.doubleTapToOpen} tTap=${prefs.tripleTapToOpen} " +
                "automation=${prefs.useAutomationForGestures}"
        )
    }

    private fun enqueue(tag: String, msg: String, tr: Throwable?) {
        if (!enabled) return
        val h = handler ?: return
        val ts = lineTs.format(Date())
        val body = if (tr != null) "$msg\n${stackToString(tr)}" else msg
        h.post { append("$ts T${Thread.currentThread().id} $tag: $body") }
    }

    private fun append(line: String) {
        val ctx = appContext ?: return
        try {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "smartedge.log")
            if (f.length() > MAX_FILE_BYTES) {
                runCatching { writer?.close() }
                writer = null
                writerFile = null
                val old = File(dir, "smartedge.log.1")
                if (old.exists()) old.delete()
                if (!f.renameTo(old)) f.delete()
            }
            val w = writer?.takeIf { writerFile == f }
                ?: FileWriter(f, true).also { writer = it; writerFile = f }
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            android.util.Log.w("DebugLog", "file write failed", e)
        }
    }

    private fun stackToString(tr: Throwable): String =
        StringWriter().also { tr.printStackTrace(PrintWriter(it)) }.toString()
}
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew assembleDebug` (mirrors workaround per Global Constraints)
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/imi/smartedge/sidebar/panel/PanelPreferences.kt \
        app/src/main/java/com/imi/smartedge/sidebar/panel/DebugLog.kt
git commit -m "feat(log): add rotating file logger (DebugLog) and preference"
```

---

### Task 2: IME detection fix + accessibility instrumentation

**Files:**
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/PanelAccessibilityService.kt` (IME tracking block ~lines 75–100; foreground filter block ~lines 232–280; `onLauncherContentEvent` ~lines 112–127)

**Interfaces:**
- Consumes: `DebugLog.i(tag, msg)` (Task 1).
- Produces: `setImeVisible(visible: Boolean, by: String)` (private; callers within this file only). Behavior: IME-visible detection matches any enabled IME's package.

- [ ] **Step 1: Add the enabled-IME cache**

Below the existing `lastImeVisible` declaration (line ~79), add:

```kotlin
    // --- Enabled-IME package cache ---
    // Third-party keyboards (WeChat wetype, Doubao, Baidu…) post window events
    // whose className does NOT contain "InputMethod" (their services are named
    // e.g. .ImeService / .WxHldService), so detection primarily matches the
    // event package against ALL enabled IME packages. Verified on-device
    // 2026-09-05: none of the user's IMEs match the old className heuristic.
    private var cachedImePackages: Set<String> = emptySet()
    private var imePkgsCachedAt = 0L

    private fun enabledImePackages(): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedImePackages.isEmpty() || now - imePkgsCachedAt > 30_000) {
            imePkgsCachedAt = now
            val raw = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: ""
            cachedImePackages = raw.split(':')
                .filter { it.isNotBlank() }
                .map { it.substringBefore('/') }
                .toSet()
        }
        return cachedImePackages
    }
```

- [ ] **Step 2: Fix setImeVisible + checkImeVisibilityFromEvent**

Replace the current `setImeVisible` (~line 81) and `checkImeVisibilityFromEvent` (~line 91) with:

```kotlin
    private fun setImeVisible(visible: Boolean, by: String) {
        if (visible == lastImeVisible) return
        lastImeVisible = visible
        DebugLog.i(TAG, "ime ${!visible}->$visible by=$by")
        android.util.Log.d(TAG, "IME visibility: $visible ($by)")
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = ACTION_IME_STATE
            putExtra("visible", visible)
        }
        startService(intent)
    }

    private fun checkImeVisibilityFromEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val className = event.className?.toString() ?: return
        val pkg = event.packageName?.toString()
        val byImePackage = pkg != null && pkg in enabledImePackages()
        if (byImePackage || className.contains("InputMethod", ignoreCase = true)) {
            setImeVisible(true, if (byImePackage) "imePkg" else "cls")
        } else if (lastImeVisible && pkg != packageName) {
            setImeVisible(false, "nonImeEvent")
        }
    }
```

Keep the existing explanatory comment block above `lastImeVisible`; update its "Open signal" line to: `// Open signal: TYPE_WINDOW_STATE_CHANGED from the IME window — matched by event package (any enabled IME) or className containing "InputMethod".`

- [ ] **Step 3: Replace the foreground-tracking filter**

In `onAccessibilityEvent`'s TYPE_WINDOW_STATE_CHANGED branch, replace the block that reads `DEFAULT_INPUT_METHOD`, computes `imePackage`/`isSystemPkg`, and guards `panelPrefs.currentForegroundPackage = packageName` (current lines ~239–262) with:

```kotlin
            val className = event.className?.toString() ?: ""

            val myPkg = this@PanelAccessibilityService.packageName
            val isSystemPkg = packageName == "android" || packageName == "com.android.systemui"
            val isImePkg = packageName in enabledImePackages()

            // Window classification for tracking + debug logs. Only real app
            // windows become "foreground": system overlays, IME windows (any
            // enabled IME — third-party keyboards don't use "InputMethod"
            // class names) and our own package must not pollute
            // currentForegroundPackage, or the onlyOnHome launcher check may
            // treat a stale/system package as "home" and re-show the handle.
            val filter = when {
                isImePkg || className.contains("InputMethod", ignoreCase = true) -> "ime"
                isSystemPkg -> "system"
                packageName == myPkg -> "own"
                else -> "app"
            }

            DebugLog.i(
                TAG, "win pkg=$packageName cls=${className.take(40)} filter=$filter" +
                    (if (filter == "app" && panelPrefs.currentForegroundPackage != packageName)
                        " fgChange=${panelPrefs.currentForegroundPackage}->$packageName" else "")
            )

            if (filter == "app") {
                panelPrefs.currentForegroundPackage = packageName
            }
```

Then replace the close-panel condition right below (current `if (packageName != "com.imi.smartedge.sidebar.panel" && packageName != imePackage && !isSystemPkg)`) with:

```kotlin
            if (filter == "app") {
```

(its body — sending ACTION_CLOSE_PANEL + ACTION_REFRESH when `panelPrefs.serviceEnabled` — stays unchanged). The now-unused `defaultIme`/`imePackage` locals must be deleted.

- [ ] **Step 4: Instrument the launcher fallback**

In `onLauncherContentEvent`, replace the throttle block:

```kotlin
        if (pkg !in cachedLauncherPkgs) return
        if (now - lastLauncherContentRefresh < 800) return
        lastLauncherContentRefresh = now
```

with:

```kotlin
        if (pkg !in cachedLauncherPkgs) return
        val throttled = now - lastLauncherContentRefresh < 800
        DebugLog.i(TAG, "launcherContent pkg=$pkg throttled=$throttled")
        if (throttled) return
        lastLauncherContentRefresh = now
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` (a leftover reference to the deleted `imePackage` local is the likely failure — remove it).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/imi/smartedge/sidebar/panel/PanelAccessibilityService.kt
git commit -m "fix(ime): detect any enabled IME package, not just InputMethod class names"
```

---

### Task 3: FloatingPanelService instrumentation + session restore

**Files:**
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/FloatingPanelService.kt` (onCreate ~line 161, onDestroy, onStartCommand, ACTION_REFRESH branch ~line 259, `isCurrentPackageLauncher` ~line 502, `addEdgeHandle` gate ~line 568)

**Interfaces:**
- Consumes: `DebugLog.i/session` (Task 1).
- Produces: `isCurrentPackageLauncher(): Boolean` unchanged signature (now logs its match branch).

- [ ] **Step 1: Session restore + lifecycle logs**

In `onCreate`, immediately after `applyNotificationVisibility()` (line ~161):

```kotlin
        applyNotificationVisibility()

        // Resume file logging after process restarts when the preference is on
        DebugLog.session(this)
        DebugLog.i(TAG, "service onCreate")
```

Locate `onDestroy()` (`grep -n "onDestroy" FloatingPanelService.kt`) and add as its first line:

```kotlin
        DebugLog.i(TAG, "service onDestroy")
```

- [ ] **Step 2: Log service actions**

Locate `override fun onStartCommand` and add as its first line:

```kotlin
        DebugLog.i(TAG, "action=${intent?.action}")
```

- [ ] **Step 3: isCurrentPackageLauncher match-branch logging**

Replace the body of `isCurrentPackageLauncher()` (keep the function signature and the FallbackHome comment) with:

```kotlin
    private fun isCurrentPackageLauncher(): Boolean {
        val currentPkg = panelPrefs.currentForegroundPackage
        if (currentPkg.isEmpty() || currentPkg == packageName) {
            DebugLog.i(TAG, "isLauncher=true by=${if (currentPkg.isEmpty()) "empty" else "own"}")
            return true // Assume home if unknown or if in our own app
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val homePkg = resolveInfo?.activityInfo?.packageName

        // Also check all installed launchers as some devices have multiple or third-party ones.
        // FallbackHome (a provisioning stub inside the Settings package) must be excluded,
        // otherwise opening the Settings app is misdetected as "on the home screen".
        val allLaunchers = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.name != "com.android.settings.FallbackHome" }
            .map { it.activityInfo.packageName }

        val by = when {
            currentPkg == homePkg -> "default"
            allLaunchers.contains(currentPkg) -> "anyLauncher"
            currentPkg == "com.android.systemui" -> "systemui"
            else -> "none"
        }
        DebugLog.i(TAG, "isLauncher=${by != "none"} by=$by fg=$currentPkg")
        return by != "none"
    }
```

- [ ] **Step 4: Gate snapshot in addEdgeHandle**

In `addEdgeHandle` (~line 568), replace:

```kotlin
        if (!anyTriggerEnabled || (panelPrefs.onlyOnHome && !isCurrentPackageLauncher()) || !hasActiveEngine || isImeVisible) {
            removeView(edgeHandleView)
            edgeHandleView = null
            return
        }
```

with:

```kotlin
        val isLauncher = !panelPrefs.onlyOnHome || isCurrentPackageLauncher()
        DebugLog.i(
            TAG,
            "handle gates: triggers=$anyTriggerEnabled isLauncher=$isLauncher " +
                "engine=$hasActiveEngine imeVis=$isImeVisible fg=${panelPrefs.currentForegroundPackage} => " +
                if (!anyTriggerEnabled || !isLauncher || !hasActiveEngine || isImeVisible) "HIDE" else "SHOW"
        )
        if (!anyTriggerEnabled || !isLauncher || !hasActiveEngine || isImeVisible) {
            removeView(edgeHandleView)
            edgeHandleView = null
            return
        }
```

- [ ] **Step 5: Gate snapshot in the ACTION_REFRESH branch**

In the ACTION_REFRESH handling (~line 259), replace:

```kotlin
                    val shouldShowHandle = if (isLandscape && !panelPrefs.showInLandscape) false
                                          else if (panelPrefs.onlyOnHome && !isCurrentPackageLauncher()) false
                                          else true
```

with:

```kotlin
                    val isLauncher = !panelPrefs.onlyOnHome || isCurrentPackageLauncher()
                    val shouldShowHandle = if (isLandscape && !panelPrefs.showInLandscape) false
                                          else if (!isLauncher) false
                                          else true
                    DebugLog.i(
                        TAG,
                        "refresh handle=$shouldShowHandle landscape=$isLandscape " +
                            "onlyOnHome=${panelPrefs.onlyOnHome} isLauncher=$isLauncher " +
                            "imeVis=$isImeVisible fg=${panelPrefs.currentForegroundPackage}"
                    )
```

- [ ] **Step 6: Build to verify**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/imi/smartedge/sidebar/panel/FloatingPanelService.kt
git commit -m "feat(log): instrument handle show/hide gates and service lifecycle"
```

---

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

### Task 5: Verification (emulator + real device)

**Files:**
- No source changes expected; commit only if fixes were needed.

**Interfaces:**
- Consumes: the installed debug APK from Tasks 1–4.

- [ ] **Step 1: Emulator smoke** (SDK at C:\Android, AVD `dylike_test`, adb `C:\Android\adb.exe`; boot `cd /c/Android/emulator && ./emulator.exe -avd dylike_test -no-snapshot-save -gpu auto` background, wait `getprop sys.boot_completed` = 1, unlock `input keyevent 82`, install `adb install -r app/build/outputs/apk/debug/app-debug.apk`, grant `adb shell appops set com.imi.smartedge.sidebar.panel SYSTEM_ALERT_WINDOW allow`)
  - Enable the debug switch in Misc settings (drive via `adb exec-out screencap -p > shot.png` + `input tap`).
  - Open/close any text field with the emulator IME; press Home; open the app again.
  - Tap Export Logs → confirm toast + `adb shell ls /sdcard/Download/SidePanel/` shows `smartedge_log_*.txt`; `adb shell head -40` of it shows the session header (`==== session …`) and `win …`/`handle gates …`/`isLauncher=…` lines.
  - `adb logcat -d | grep -E "FATAL|AndroidRuntime" | grep -i smartedge` → empty.

- [ ] **Step 2: Real-device IME proof** (Honor BKQ-AN10, WeChat wetype + Doubao IME installed — device may be attached: check `adb devices -l`)
  - Install the debug APK (`adb install -r`), enable Debug Logging in settings.
  - With WeChat IME as default: focus a text field (keyboard opens) → `adb shell dumpsys window | grep -c u0 InputMethod` shows the IME window; close keyboard.
  - Switch system IME to Doubao; repeat.
  - Export logs (settings button), then `adb shell cat /sdcard/Download/SidePanel/smartedge_log_*.txt | grep -E "ime|handle"` — expect lines `ime false->true by=imePkg` (for `com.tencent.wetype` and later `com.bytedance.android.doubaoime` events) and the handle gate line ending `=> HIDE` while the keyboard is open. These lines are the direct proof of the fix.
  - If no device attached, defer Step 2 to the user and note it in the report.

- [ ] **Step 3: Final gate**

Run: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`. If any step required a code fix, commit with `fix(log): ...` or `fix(ime): ...`; otherwise no commit.
