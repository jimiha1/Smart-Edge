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

