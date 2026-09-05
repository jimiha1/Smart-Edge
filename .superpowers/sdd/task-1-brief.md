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

