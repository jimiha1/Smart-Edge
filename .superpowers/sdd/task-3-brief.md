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

