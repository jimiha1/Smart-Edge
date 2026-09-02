# 从最近任务隐藏（Hide from Recents）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a default-on setting that hides the app's task from Recents (preventing the panel from being swipe-killed on OEM ROMs), implemented via dual launcher activity-aliases, plus explicit `stopWithTask="false"` on the panel service.

**Architecture:** `MainActivity` loses its LAUNCHER entry; two `activity-alias` components (`LauncherHidden` with `excludeFromRecents`, `LauncherVisible` without) take over as alternate manifest entries. A small helper flips them at runtime with `PackageManager.setComponentEnabledSetting(DONT_KILL_APP)` and re-syncs component state to the preference on every app start. A MaterialSwitch in Miscellaneous settings drives it.

**Tech Stack:** Plain Kotlin (no DI, no Compose), Android Views + ViewBinding, Material 3, single `:app` module. Spec: `docs/superpowers/specs/2026-09-02-hide-from-recents-design.md`.

## Global Constraints

- JDK 17, commands run from repo root in Git Bash on Windows: `./gradlew assembleDebug`, `./gradlew lint`.
- `./gradlew lint` has `abortOnError = true` — every task must leave lint passing.
- **No unit-test source sets exist in this repo** (per AGENTS.md). Verification per task = build + lint; behavioral verification is the emulator acceptance checklist in Task 4. Do not add test infrastructure for this feature.
- All SharedPreferences access goes through `PanelPreferences` property accessors — never direct `SharedPreferences`.
- User-facing strings must be added to all three locales: `values/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml`. Chinese uses half-width punctuation without trailing period (match neighboring strings).
- New preference `hideFromRecents` is intentionally NOT added to the backup export whitelist (device-level UI state; mirrors existing `hideServiceNotification`, which is also not exported). `RecentsHideHelper.sync()` reconciles state on app start.
- Every `setComponentEnabledSetting` call uses `PackageManager.DONT_KILL_APP`.
- No new dependencies. Manifest `tools:ignore` annotations on existing permissions stay untouched.
- Commit style: conventional prefixes (`feat(scope)`, `fix(scope)`, `chore`).

---

### Task 1: Manifest — dual launcher aliases + service hardening

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (MainActivity block at lines 48–58; FloatingPanelService block at lines 129–136)

**Interfaces:**
- Consumes: nothing new.
- Produces: component names `com.imi.smartedge.sidebar.panel.LauncherHidden` (manifest-enabled, `excludeFromRecents="true"`) and `com.imi.smartedge.sidebar.panel.LauncherVisible` (manifest-disabled) — Task 2's `RecentsHideHelper` references these exact names. `MainActivity` becomes non-exported with no intent-filter.

- [ ] **Step 1: Replace the MainActivity block**

Replace this block (lines 48–58):

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
        </activity>
```

with:

```xml
        <!-- Entry point is provided by the LauncherHidden / LauncherVisible aliases below,
             so the launcher entry can be swapped at runtime to toggle Recents visibility. -->
        <activity
            android:name=".MainActivity"
            android:exported="false" />

        <!-- Default entry (matches hideFromRecents default = true): task hidden from Recents. -->
        <activity-alias
            android:name=".LauncherHidden"
            android:targetActivity=".MainActivity"
            android:exported="true"
            android:enabled="true"
            android:excludeFromRecents="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
        </activity-alias>

        <!-- Alternate entry used when the user disables "Hide from Recents". -->
        <activity-alias
            android:name=".LauncherVisible"
            android:targetActivity=".MainActivity"
            android:exported="true"
            android:enabled="false">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
        </activity-alias>
```

Both aliases inherit the application icon/label (`@mipmap/ic_launcher` / `@string/app_name`), so the home-screen icon looks identical. `@xml/shortcuts` targets `ToggleActivity`, which is unaffected by the alias switch.

- [ ] **Step 2: Harden FloatingPanelService**

In the `FloatingPanelService` declaration, add `android:stopWithTask="false"`:

```xml
        <service
            android:name=".FloatingPanelService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="specialUse">
```

(This is the documented default, declared explicitly so removing a task never stops the service — see https://developer.android.com/guide/topics/manifest/service-element)

- [ ] **Step 3: Build to verify**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. A manifest merge error mentioning `activity-alias` or exported requirements means the XML is malformed — re-check against Step 1.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(recents): hide app task from Recents via dual launcher aliases"
```

---

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

### Task 3: Settings UI — toggle in Miscellaneous settings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (after line 149)
- Modify: `app/src/main/res/values-es/strings.xml` (after line 150)
- Modify: `app/src/main/res/values-zh/strings.xml` (after line 150)
- Modify: `app/src/main/res/layout/activity_settings_misc.xml` (after the `misc_hide_notification_desc` TextView)
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/MiscellaneousSettingsActivity.kt` (after the `featureHideNotification` listener block, lines 58–65)

**Interfaces:**
- Consumes: `PanelPreferences.hideFromRecents` (Task 2), `RecentsHideHelper.apply(context, hidden)` (Task 2), ViewBinding id `feature_hide_recents` → `binding.featureHideRecents`.
- Produces: nothing consumed later.

- [ ] **Step 1: Add strings in all three locales**

`values/strings.xml` (after `misc_hide_notification_desc`):

```xml
    <string name="misc_hide_recents_label">Hide from Recents</string>
    <string name="misc_hide_recents_desc">Keeps the panel alive by hiding this app from Recents; takes effect on next launch. The home-screen icon may briefly refresh when toggled</string>
```

`values-zh/strings.xml`:

```xml
    <string name="misc_hide_recents_label">从最近任务隐藏</string>
    <string name="misc_hide_recents_desc">将应用从最近任务中隐藏,防止清理后台时误杀侧边栏;对新启动生效。切换开关时桌面图标可能短暂刷新,属正常现象</string>
```

`values-es/strings.xml`:

```xml
    <string name="misc_hide_recents_label">Ocultar de apps recientes</string>
    <string name="misc_hide_recents_desc">Oculta la app de las apps recientes para evitar que el panel se cierre al limpiar la memoria; surte efecto en el próximo inicio. El icono puede actualizarse brevemente al cambiarlo</string>
```

- [ ] **Step 2: Add the switch to the layout**

In `activity_settings_misc.xml`, inside the same card as `feature_hide_notification`, directly after the `misc_hide_notification_desc` TextView:

```xml
                    <!-- Hide from Recents -->
                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/feature_hide_recents"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/misc_hide_recents_label"
                        android:textColor="?attr/colorOnSurface"
                        android:textSize="16sp"
                        android:paddingVertical="12dp" />

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/misc_hide_recents_desc"
                        android:textColor="?attr/colorOnSurfaceVariant"
                        android:textSize="14sp"
                        android:layout_marginBottom="8dp" />
```

(Structure and attributes mirror the neighboring `feature_hide_notification` block exactly.)

- [ ] **Step 3: Wire up the activity**

In `MiscellaneousSettingsActivity.onCreate()`, after the existing `featureHideNotification` listener block (lines 58–65), add:

```kotlin
        val hideRecentsListener = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
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
                binding.root.showModernToast("Couldn't change Recents visibility: ${e.message}")
            }
        }
        binding.featureHideRecents.isChecked = panelPrefs.hideFromRecents
        binding.featureHideRecents.setOnCheckedChangeListener(hideRecentsListener)
```

Note the listener is attached **after** the initial `isChecked` assignment so the programmatic set does not trigger it.

- [ ] **Step 4: Build and lint to verify**

Run: `./gradlew assembleDebug lint`
Expected: `BUILD SUCCESSFUL` for both. Lint failures about missing translations or unused resources must be fixed before committing (`abortOnError = true`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/res/layout/activity_settings_misc.xml \
        app/src/main/java/com/imi/smartedge/sidebar/panel/MiscellaneousSettingsActivity.kt
git commit -m "feat(recents): add hide-from-Recents toggle in misc settings"
```

---

### Task 4: Emulator acceptance (spec verification checklist)

**Files:**
- No source changes expected; commit only if fixes were needed.

**Interfaces:**
- Consumes: the installed debug APK from Tasks 1–3.

Use the android-emulator MCP tools (`android_preflight` → `android_build_and_run` → `android_screenshot` / `android_ui_resolve` / `android_ui_tap` / `android_ui_keyevent APP_SWITCH`, plus `adb` for `pm clear`). The emulator is the only place alias switching, Recents behavior, and shortcuts can be validated.

- [ ] **Step 1: Build, install, and launch**

`android_build_and_run` (projectDir `.`, variant `debug`). Expected: app installs and opens via the `LauncherHidden` alias; home-screen icon present with normal label/icon.

- [ ] **Step 2: Recents hidden by default**

Open the app, press Home, then trigger Recents (`android_ui_keyevent` APP_SWITCH or the gesture nav pill). Expected: **no Smart Edge card**. Any other open app still shows normally.

- [ ] **Step 3: Toggle off — card reappears**

In the app: Miscellaneous settings → toggle 「从最近任务隐藏 / Hide from Recents」 off. Press Home, open Recents. Expected: Smart Edge card present (this task's card, launched while visible). Foreground service notification still present after the toggle (no process restart).

- [ ] **Step 4: Toggle on — new launches hidden**

Toggle back on, press Home, relaunch from icon, press Home again, open Recents. Expected: no new Smart Edge card.

- [ ] **Step 5: Static shortcuts in both states**

Long-press the home-screen icon in both toggle states. Expected: the 「Toggle Sidebar」 static shortcut (from `@xml/shortcuts`) appears and works in both.

- [ ] **Step 6: Clear-data self-heal**

Run `adb shell pm clear com.imi.smartedge.sidebar.panel`, then relaunch from the icon and open the app once. Expected: preference resets to default (toggle on in settings UI); alias state reconciled on first app start — next launch's task stays hidden from Recents.

- [ ] **Step 7: No crashes**

`adb logcat -d | grep -i "FATAL\|AndroidRuntime"` — Expected: no crashes attributed to `com.imi.smartedge.sidebar.panel` during the whole run.

- [ ] **Step 8: Final gate and commit (only if fixes were made)**

Run: `./gradlew lint`
Expected: `BUILD SUCCESSFUL`. If any step required a code fix, commit with `fix(recents): ...`; otherwise no commit.
