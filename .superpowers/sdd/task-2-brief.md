### Task 2: Runtime switching engine (PanelPreferences + RecentsHideHelper + app-start sync)

**Files:**
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/PanelPreferences.kt` (companion constants near line 39; property near line 640)
- Create: `app/src/main/java/com/imi/smartedge/sidebar/panel/RecentsHideHelper.kt`
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/SidePanelApp.kt` (onCreate, after `applyAppTheme(this)` at line 17)

**Interfaces:**
- Consumes: alias component names produced by Task 1.
- Produces (used by Task 3):
  - `PanelPreferences.hideFromRecents: Boolean` — custom var, default `true`
  - `RecentsHideHelper.apply(context: Context, hidden: Boolean)` — flips both aliases; throws on PackageManager failure
  - `RecentsHideHelper.sync(context: Context)` — no-op or correction toward the preference

- [ ] **Step 1: Add the preference constant**

In `PanelPreferences` companion object, next to `KEY_HIDE_NOTIFICATION` (line ~39):

```kotlin
        private const val KEY_HIDE_NOTIFICATION = "hide_service_notification"
        private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
```

- [ ] **Step 2: Add the property accessor**

Immediately after the existing `hideServiceNotification` property (line ~640):

```kotlin
    /**
     * Exclude the app's task from Recents (default on). Implemented by toggling
     * the launcher activity-aliases — see RecentsHideHelper. Not part of backup
     * export: it is device-level UI state, like hideServiceNotification.
     */
    var hideFromRecents: Boolean
        get() = prefs.getBoolean(KEY_HIDE_FROM_RECENTS, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_FROM_RECENTS, value) }
```

- [ ] **Step 3: Create RecentsHideHelper.kt**

New file `app/src/main/java/com/imi/smartedge/sidebar/panel/RecentsHideHelper.kt` (flat package, matching `*Helper` naming):

```kotlin
package com.imi.smartedge.sidebar.panel

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches the manifest launcher entry between the LauncherHidden and
 * LauncherVisible activity-aliases so the app's task can be excluded from
 * Recents at runtime. This prevents the panel service from being
 * swipe-killed on OEM ROMs that terminate the process when a task card
 * is dismissed. Only one alias is enabled at a time; both share the
 * application icon/label, so the home-screen icon looks unchanged.
 */
object RecentsHideHelper {

    private fun hiddenAlias(context: Context) =
        ComponentName(context, "${context.packageName}.LauncherHidden")

    private fun visibleAlias(context: Context) =
        ComponentName(context, "${context.packageName}.LauncherVisible")

    /**
     * Enables exactly one of the two launcher aliases. Pass hidden = true to
     * exclude the app's task from Recents. Uses DONT_KILL_APP so the running
     * foreground service is not restarted by the switch.
     */
    fun apply(context: Context, hidden: Boolean) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            hiddenAlias(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            visibleAlias(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /**
     * Reconciles alias component state with the hideFromRecents preference.
     * Covers drift such as component state surviving an app-data clear, or a
     * partially-applied switch. Safe to call on every app start; it is a no-op
     * when the states already agree (DEFAULT state counts as the manifest
     * default, which matches the preference default).
     */
    fun sync(context: Context) {
        val hidden = PanelPreferences(context).hideFromRecents
        val pm = context.packageManager
        val wantHidden = if (hidden) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val wantVisible = if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val hiddenOk = pm.getComponentEnabledSetting(hiddenAlias(context)) == wantHidden
        val visibleOk = pm.getComponentEnabledSetting(visibleAlias(context)) == wantVisible
        if (!hiddenOk || !visibleOk) apply(context, hidden)
    }
}
```

- [ ] **Step 4: Sync on app start**

In `SidePanelApp.onCreate()`, immediately after `applyAppTheme(this)` (line 17):

```kotlin
        // Apply the saved theme mode
        applyAppTheme(this)

        // Keep launcher alias components in sync with the hideFromRecents preference
        // (corrects drift, e.g. component state surviving an app-data clear)
        RecentsHideHelper.sync(this)
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/imi/smartedge/sidebar/panel/PanelPreferences.kt \
        app/src/main/java/com/imi/smartedge/sidebar/panel/RecentsHideHelper.kt \
        app/src/main/java/com/imi/smartedge/sidebar/panel/SidePanelApp.kt
git commit -m "feat(recents): runtime alias switching engine (RecentsHideHelper)"
```

---

