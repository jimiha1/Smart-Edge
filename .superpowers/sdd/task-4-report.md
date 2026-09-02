# Task 4 Report: Emulator acceptance — "Hide from Recents"

Date: 2026-09-02. Branch: `feat/hide-from-recents` (HEAD 8dddf18).
APK: `D:\Smart-Edge\app\build\outputs\apk\debug\app-debug.apk` (built 21:22, includes Tasks 1–3).
Device: AVD `dylike_test`, android-35 (API 35) default x86_64 emulator, 1080x2400.

## Environment notes / deviations

- MCP android-emulator tools unusable (ANDROID_HOME unset there); everything done via Bash + `C:\Android\adb.exe` as instructed.
- Emulator needed `ANDROID_AVD_HOME=C:\Users\jiangyunfei\.android\avd` and `ANDROID_SDK_HOME` unset (shell profile sets it to `C:\Android`, hiding the AVD).
- Pre-granted: `appops SYSTEM_ALERT_WINDOW allow`, `POST_NOTIFICATIONS`. **Deviation:** the app's accessibility service (`PanelAccessibilityService`) had to be enabled via `settings put secure enabled_accessibility_services` — required to pass SetupActivity's Continue gate (`requiredGranted = hasOverlay && (hasAccessibility || hasAutomation)`); without it the app cannot be driven past first-run setup at all. Note: both `am force-stop` and `pm clear` silently disable the a11y binding (system behavior), requiring re-enable each time.
- Recents-overview oracle: synthetic swipes could not page the quickstep carousel reliably, so card presence was verified with `uiautomator dump` on the overview (each `com.android.launcher3:id/task` node's `content-desc` is the card label) plus screenshots, and cross-checked with `dumpsys activity recents`.

## Step results

### Step 1: Build, install, launch — PASS
- `adb install -r` → `Success`.
- Fresh-install alias state: `cmd package resolve-activity ... LAUNCHER` → `com.imi.smartedge.sidebar.panel/.LauncherHidden` (manifest default).
- App drawer (shot-02) shows icon with normal label "Smart Edge"; icon node bounds [216,924][432,1239].
- Launch via icon → task #44 created; `dumpsys activity`: `mLastPausedActivity .../.LauncherHidden`, `topResumedActivity .../.SetupActivity` (first-run). Shot-03.

### Step 2: Recents hidden by default — FAIL (primary defect D1)
- Opened app (SetupActivity, pid 2136), Home, opened Settings app, Home, APP_SWITCH → Recents overview.
- Screenshot shot-04: Settings card visible. `uiautomator dump` of the overview **also shows a `content-desc="Smart Edge"` task card** peeking at the left edge (bounds [0,144][131,1943]).
- Tapping that sliver card (tap 65,1088) brought `com.imi.smartedge.sidebar.panel/.SetupActivity` to the foreground (mCurrentFocus, shot-14) — the card is real, tappable, and resumes the app.
- After completing setup and dismissing the stale task, a **fresh launch purely via the LauncherHidden alias** (task #46: `intent cmp=.../.LauncherHidden`, `origActivity=.../.LauncherHidden`, root ActivityRecord = LauncherHidden) still produced a visible Smart Edge card (overview dump + shot-17).
- Framework confirms the flag is not applied: `dumpsys activity recents` for that task shows `isExcluded=false`.
- **A/B diagnostic:** `am start -n .../.LauncherHidden -f 0x00800000` (FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) → task does NOT appear in overview (only "DyLike测试" + "Settings" cards). The platform's exclusion machinery works; it is specifically the **activity-alias manifest attribute that is ignored on API 35**.
- APK compiled manifest verified via `aapt dump xmltree`: `LauncherHidden` has `android:excludeFromRecents=0xffffffff`, `enabled=true`; `LauncherVisible` `enabled=false`. The manifest is correct — the mechanism is what fails.
- Additional first-run nuance (D2): `MainActivity.onCreate` does `startActivity(SetupActivity); finish()` on fresh installs, so the task root becomes SetupActivity (which has no excludeFromRecents) — even on platforms where the alias attribute works, the first-run task would be visible in Recents.
- "Any other open app still shows normally": PASS (Settings card visible throughout).

### Step 3: Toggle off — card reappears — PASS (sub-checks noted)
- Navigated app → Advanced Settings → SidePanel Settings → Miscellaneous. Switch `feature_hide_recents` present with text "Hide from Recents", `checked="true"` default (shot-19).
- Toggled OFF (shot-20). Component states after: `disabledComponents: LauncherHidden`, `enabledComponents: LauncherVisible`; `resolve-activity` → `.LauncherVisible`. Task launched via icon then has `cmp/origActivity=.../.LauncherVisible`.
- Recents after Home: **Smart Edge card present** (overview dump + shot-21) — expected for visible mode. PASS.
- "Foreground service notification still present after the toggle (no process restart)": PARTIAL — no FGS notification exists in accessibility-engine mode (none posted; the FGS notification only appears once FloatingPanelService is started, e.g. via the Toggle Sidebar shortcut; logcat: `Background started FGS: Allowed ... FloatingPanelService`). The "no process restart" intent was verified directly: **pid 2726 before and after the toggle** (`DONT_KILL_APP` honored).

### Step 4: Toggle on — new launches hidden — FAIL (D1 again)
- Toggled back ON (switch `checked` → on via UI; `resolve-activity` → `.LauncherHidden`, `disabledComponents: LauncherVisible`).
- The previously visible-alias-rooted task disappeared from Recents (disabling its root component killed it — expected).
- Fresh relaunch via icon (task #50, `cmp/origActivity=.../.LauncherHidden`), Home, Recents → **Smart Edge card still present** (overview dump + shot-22). Expected per spec: absent. FAIL — same platform defect as Step 2.

### Step 5: Static shortcuts in both states — PASS
- Toggle ON (hidden alias): long-press drawer icon (swipe 324,1081 same-point 900ms) → menu shows App info / Widgets / **"Sidebar"** (matches `@xml/shortcuts` shortcutShortLabel `shortcut_toggle_short` = "Sidebar"). Shot-23. Tapping it: logcat `START u0 {act=com.imi.smartedge.sidebar.panel.TOGGLE ... cmp=.../.ToggleActivity bnds=[256,796][823,933]} with LAUNCH_SINGLE_INSTANCE ... result code=0`; FloatingPanelService started and two SYSTEM_ALERT_WINDOW app windows appeared (panel overlay).
- Toggle OFF (visible alias): long-press → "Sidebar" shortcut present again (shot-24); tapping fired the same ToggleActivity START (13:59:01.152). Works in both states.

### Step 6: Clear-data self-heal — PARTIAL (2/3 PASS)
- Pre-clear drift: `disabledComponents: LauncherHidden`, `enabledComponents: LauncherVisible` (from Step 3/4 toggling). `pm clear` → Success; **component states survive the clear** (same drift — exactly the scenario `sync()` targets). (pm clear also disabled the a11y binding; re-enabled + re-granted overlay.)
- First app start after clear (goes to SetupActivity): **`RecentsHideHelper.sync()` reconciled correctly** — `disabledComponents: LauncherVisible`, `enabledComponents: LauncherHidden`, `resolve-activity` → `.LauncherHidden`. PASS.
- Preference default: after re-completing setup, Miscellaneous switch `feature_hide_recents` `checked="true"` (shot-25). PASS.
- "Next launch's task stays hidden from Recents": FAIL — fresh post-clear launch shows the Smart Edge card in overview (dump + shot-26). Same platform defect D1.

### Step 7: No crashes — PASS
- `adb logcat -d | grep -E "FATAL|AndroidRuntime" | grep -i smartedge` → empty. Zero `FATAL EXCEPTION` lines in the whole buffer for the entire session.

### Step 8: Final gate (`./gradlew lint`) — FAIL (pre-existing, not feature-related)
- First run failed at Initialization: user env script `C:\Users\jiangyunfei\.gradle\init.d\mirrors.gradle` conflicts with settings-repositories policy ("repository 'Google' was added by initialization script"). Worked around by temporarily moving the script aside (restored afterwards).
- With the script aside, lint ran: `BUILD FAILED`, **55 errors, 658 warnings** (`app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`).
- None of the 55 errors are attributable to this feature: error files are `side_panel_layout.xml` (8), `activity_support.xml` (6), `Extensions.kt` (4), `PanelTileService.kt` (2), etc.; MissingTranslation errors flag strings like `feature_landscape_*` / `section_desktop_shortcuts` — the branch only added `misc_hide_recents_label` / `misc_hide_recents_desc`, which are translated and not flagged. Zero errors in `RecentsHideHelper.kt`, `SidePanelApp.kt`, `PanelPreferences.kt`, `MiscellaneousSettingsActivity.kt`, `activity_settings_misc.xml`. The manifest error is line 11 (camera permission), unrelated to the aliases (lines 48–85).
- No code fixes were made by the verifier → **no commit** (per brief).

## Defects

### D1 (CRITICAL — feature goal not achieved on API 35): alias `excludeFromRecents` ignored
- **What:** A task launched through the `LauncherHidden` activity-alias (manifest `android:excludeFromRecents="true"`, verified present in the compiled APK) still shows a card in the Recents overview, and the card is tappable (resumes the app).
- **Evidence:** overview `uiautomator dump` cards (`content-desc="Smart Edge"`), shots 04/16/17/21/22/26; `dumpsys activity recents` → `isExcluded=false` for task rooted at the alias; A/B test: adding intent flag `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` (0x00800000) DOES hide the task → platform honors the intent flag, not the alias attribute.
- **Repro:** install debug APK → launch via icon (toggle ON default) → Home → APP_SWITCH → Smart Edge card present.
- **Impact:** Steps 2, 4, and the final sub-check of Step 6 fail. The feature's stated purpose (preventing task swipe-kill on OEM ROMs) is not achieved on this platform.
- **Fix direction (not implemented):** complement the alias approach with the runtime intent flag — e.g., `MainActivity` re-launching/finishing trampoline pattern that stamps `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` when `hideFromRecents` is on (launcher-initiated intents can't be controlled by the app), or re-evaluate the mechanism for target Android versions.

### D2 (minor): first-run setup re-roots the task without excludeFromRecents
- `MainActivity.onCreate` (`if (!panelPrefs.setupCompleted) { startActivity(SetupActivity); finish() }`) makes SetupActivity the task root on first run; SetupActivity lacks `excludeFromRecents`, so even on platforms where the alias attribute works, the first-run task would appear in Recents.
- Evidence: `dumpsys activity recents` task #44 `Activities=[SetupActivity] rootOfTask=true`; MainActivity.kt lines 50–52.

### D3 (info): lint gate fails with 55 pre-existing errors unrelated to the feature
- `./gradlew lint` → BUILD FAILED (55 errors) — contradicts AGENTS.md's expectation that lint passes; all errors pre-date/outside the feature files (see Step 8). Also note the user-level `mirrors.gradle` init script breaks every shell Gradle invocation with an Initialization error (workaround documented above).

## Screenshots saved (D:\Smart-Edge\.superpowers\sdd\)
- shot-01-home.png — post-install home screen
- shot-02-appdrawer.png — app drawer with Smart Edge icon
- shot-03-app-launched.png — first launch (SetupActivity)
- shot-04-recents-default-hidden.png — Recents after first launch (Settings card; Smart Edge sliver at left)
- shot-05/06/07-recents-swipe*.png — carousel attempts (identical)
- shot-09-recents-right1.png — drag exited to home
- shot-11/12 — further carousel attempts
- shot-13-recents-edge-card.png, shot-14-after-edge-card-tap.png — Smart Edge card tapped → SetupActivity foreground (D1 proof)
- shot-15-after-setup-continue.png — setup completed → MainActivity
- shot-16-recents-after-setup.png — Recents with MainActivity-rooted task (card present)
- shot-17-recents-fresh-launch.png — fresh alias-rooted task card (D1 proof)
- shot-18-service-started.png — service start attempt (routed to a11y settings)
- shot-19-misc-toggle-on.png / shot-20-misc-toggle-off.png — switch states
- shot-21-recents-toggle-off.png — visible alias → card present (Step 3 PASS)
- shot-22-recents-toggle-on-fresh.png — fresh hidden-alias launch → card present (Step 4 FAIL)
- shot-23-shortcut-hidden-state.png / shot-24-shortcut-visible-state.png — long-press shortcut menus in both states
- shot-25-postclear-toggle-default.png — post-clear default toggle ON
- shot-26-postclear-recents.png — post-clear launch card present (Step 6 sub-check FAIL)

Supporting dumps: ui-recents.xml (overview cards), ui-setup2.xml, ui-misc2.xml, emulator.log.

## Fix run 1 (D1+D2)

Date: 2026-09-02. Branch `feat/hide-from-recents`, commit `d0e4a10` ("fix(recents): enforce Recents exclusion via runtime intent flag on task roots", 3 files, +66).

### What changed

- `RecentsHideHelper.kt` — new `ensureExcluded(activity): Boolean`. When the task's Recents exclusion state (base-intent flag) disagrees with the `hideFromRecents` preference, the activity re-launches itself into a fresh task whose base intent carries `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` (or drops it when showing again), removes stale sibling tasks via `AppTask.finishAndRemoveTask()`, and returns true so the caller returns from `onCreate` immediately.
- `MainActivity.kt` / `SetupActivity.kt` — `if (RecentsHideHelper.ensureExcluded(this)) return` as the first work line of `onCreate`, right after `super.onCreate(...)` and before anything else (covers D2: SetupActivity-as-root on first run).

**Design deviation (deliberate, evidence below):** the specified relaunch used `NEW_TASK | CLEAR_TASK | EXCLUDE`. On this API 35 emulator a CLEAR_TASK relaunch reuses the existing task WITHOUT restamping its base intent — dumpsys kept `intent={... flg=0x10200000 cmp=.../.LauncherHidden}` (no 0x00800000) while the new root ActivityRecord carried `flg=0x10808000`, and `RecentTaskInfo.isExcluded=false` with the card still rendered. Iterative A/B on-emulator (am start flag matrices) established:
1. The flag only takes effect when present on the intent that CREATES the task → relaunch must use `FLAG_ACTIVITY_MULTIPLE_TASK` (forces a fresh task) instead of CLEAR_TASK.
2. A plain `finish()` leaves a recents tombstone (`hasTask=false`) for the original launcher task that still renders a card → the old task is removed with `AppTask.finishAndRemoveTask()` / alias-rooting.
3. After an in-app toggle the launcher relaunch stacks the new root on the still-alive stale task (no `onCreate` for the old root), so the mismatch check reads the TASK's base intent via `ActivityManager.getAppTasks()` (not just `activity.intent`) and reconciles in both directions (hidden<->shown).
4. The fresh task is rooted at the enabled launcher alias matching the preference; disabling an alias kills tasks rooted at it (observed again here), so toggling the preference tears down the stale task and the next launch rebuilds correct flag state — this is what makes the OFF direction (card PRESENT) work.

### Build

`./gradlew assembleDebug` → `BUILD SUCCESSFUL` (mirrors.gradle moved aside and restored around the run, as before).

### Verification (AVD dylike_test, API 35; oracle = settled overview uiautomator card count + `dumpsys activity recents` RecentTaskInfo)

- **V1 PASS** — pm clear → monkey launch → completed setup (Continue) → Home → Recents: 0 "Smart Edge" cards, 0 smartedge RecentTaskInfo entries; single live task `flg=0x18800000 cmp=.../.LauncherHidden` (EXCLUDE present). Note: opening Recents right after APP_SWITCH can transiently render the just-excluded card for a few seconds before the overview re-binds (stale snapshot); the settled state (6 s) and dumpsys are clean. Screenshots: fix1-shot-v1-setup.png, fix1-shot-v1-app.png, fix1-shot-v1-recents.png.
- **V2 PASS** — toggle OFF (Miscellaneous → "Hide from Recents", checked true→false; alias switch verified) → relaunch: fresh unflagged task `flg=0x10200000 cmp=.../.LauncherVisible`, Recents card PRESENT (1 card). Toggle ON → relaunch: task `flg=0x18800000 cmp=.../.LauncherHidden`, Recents card ABSENT (0 cards, 0 entries). Screenshots: fix1-shot-v2-toggle-on.png, fix1-shot-v2-toggle-off.png, fix1-shot-v2-off-app.png, fix1-shot-v2-off-recents.png (card present), fix1-shot-v2-toggle-on2.png, fix1-shot-v2-on-recents.png (absent).
- **V3 PASS** — no crash during the whole cycle; switch state persisted across relaunches (Misc screen showed `checked="false"` after relaunching with pref off; toggling ON killed the visible-alias-rooted task exactly as the platform does, then the next launch rebuilt hidden state).
- **V4 PASS (D2)** — pm clear → monkey launch → SetupActivity foreground (first run) → Home → Recents: card ABSENT (0 cards, 0 entries); task rooted at LauncherHidden with EXCLUDE flag. Screenshots: fix1-shot-v4-setup.png, fix1-shot-v4-recents.png.
- **V5 PASS** — `adb logcat -d | grep -E "FATAL|AndroidRuntime" | grep -i smartedge` → empty.

Environment notes: emulator needed `ANDROID_AVD_HOME=C:\Users\jiangyunfei\.android\avd` (shell profile's `ANDROID_SDK_HOME=C:\Android` hides the AVD); a11y service had to be re-enabled via `settings put secure ...` after each force-stop/pm clear to pass Setup's Continue gate.

### Residual concerns

- The overview can flash the excluded card for ~1-3 s after opening Recents (stale snapshot before the launcher re-binds); the settled state is clean. Cosmetic, platform-side.
- Toggling the preference kills the live task mid-session (screen falls to Home) — identical to the pre-fix platform behavior for alias switches, but now it also happens for tasks rooted via the relaunch path. Accepted trade-off of the alias-rooting mechanism.
- `ensureExcluded` deviates from the mandated sketch as documented above; call sites, method name/signature, and placement are exactly as specified.

## Fix run 2 (review findings F1-F7)

Commit: `bfba7d4` fix(recents): harden relaunch path and narrow task cleanup

### Findings

- **F1 (unguarded remote task-mutation calls)** — `RecentsHideHelper.kt:118` (sibling loop) and `:139-141` (trailing old-task removal) wrap each `finishAndRemoveTask()` in `runCatching`; `startActivity(relaunch)` at `:137` is wrapped in `runCatching` and on failure the method returns `false` **without** calling `activity.finish()` — contract "returns true ⟺ caller was re-launched" now holds; on success the trailing removal is runCatching'd, then `finish()`, `return true` (`:142-144`).
- **F2 (id-only sibling cleanup)** — `RecentsHideHelper.kt:107-118`: sibling filter now requires same `packageName` and base-intent component `className` ending in `.MainActivity` / `.SetupActivity` / `.LauncherHidden` / `.LauncherVisible`; comment updated to say unrelated tasks (e.g. the shortcut trampoline) are left alone.
- **F3 (null-inverted polarity)** — `RecentsHideHelper.kt:99-104`: both checks use explicit `(... ?: 0).and(FLAG) != 0` so a null short-circuit reads as "not excluded".
- **F4 (sync KDoc)** — `RecentsHideHelper.kt:50-53`: parenthetical now reads "a fresh install's DEFAULT states are pinned to explicit states on the first sync".
- **F5 (apply ordering)** — `RecentsHideHelper.kt:24-47`: picks `(incoming, outgoing)` by direction, enables incoming before disabling outgoing; KDoc adds "Throws if the underlying PackageManager call fails — callers handle rollback."
- **F6 (lifecycle comments)** — `MainActivity.kt:38` and `SetupActivity.kt:30`: one-line comment above each `if (RecentsHideHelper.ensureExcluded(this)) return` noting the early return finishes in onCreate so onStart/onResume never run.
- **F7 (zh punctuation)** — `values-zh/strings.xml:152`: full-width `。` after 对新启动生效 replaced with half-width `,`.

### Verification

Build: `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 28s` (only pre-existing `taskInfo.id` deprecation warnings; no lint/test regressions). Emulator `dylike_test` (API 35), APK installed, overlay + accessibility granted via adb.

- **R1 fresh state (PASS)** — `pm clear`, cold launch, setup completed via UI (overlay pre-granted; accessibility enabled through `settings put secure` + relaunch, then Continue). Home → Recents: **card ABSENT**. Shots: `.superpowers/sdd/fix2-shot-r1-1.png` (setup), `fix2-shot-r1-2.png` (setup scrolled), `fix2-shot-r1-3.png` (permissions granted), `fix2-shot-r1-4.png` (main screen), `fix2-shot-r1-recents.png` (Recents, no card).
- **R2 toggle cycle (PASS)** — Misc settings → switch OFF (task torn down by alias disable, fell to Home as designed) → relaunch → Recents **card PRESENT** (`fix2-shot-r2-off-recents.png`); switch ON → relaunch → Recents **card ABSENT** (`fix2-shot-r2-on-recents.png`); switch state persisted OFF and ON across relaunches (re-read `checked=` from UI dumps both times).
- **R3 shortcut trampoline (PASS)** — app-drawer icon long-press → "Sidebar" shortcut fired with hide ON (no crash, alias still LauncherHidden) and with hide OFF (no crash, alias correctly LauncherVisible). Note: this shortcut toggles the sidebar panel, not the recents pref.
- **R4 crash scan (PASS)** — `adb logcat -d | grep -E "FATAL|AndroidRuntime" | grep -i smartedge` → empty (0 `FATAL EXCEPTION` lines in the whole buffer).

Environment note: emulator needed `ANDROID_AVD_HOME=C:\Users\jiangyunfei\.android\avd` because `ANDROID_SDK_HOME=C:\Android` otherwise hides the user-profile AVD from the emulator search path.

## Fix run 3 (final review G1-G6)

Date: 2026-09-03. Branch `feat/hide-from-recents`, commit `6d691fd` ("fix(recents): log fallback paths, disclose toggle close behavior", 6 files, +43/-15).

### Per-finding changes

- **G1 (silent exception swallowing)** — `RecentsHideHelper.kt`: added `import android.util.Log` (line 7) and `.onFailure { Log.w("RecentsHideHelper", ...) }` to all four runCatching blocks: sibling-cleanup loop `:126-129` ("Failed to remove stale sibling task"), alias probe `:139-141` ("Failed to resolve launcher alias: $aliasComponent" — was `.getOrNull()` with no logging), startActivity relaunch `:152-154` ("Relaunch into flagged task failed"), trailing finishAndRemoveTask `:157-160` ("Failed to remove original task after relaunch"). Literal-tag style matches `SidePanelApp`/`AppIconModelLoader`.
- **G2 (copy doesn't disclose kick-to-Home)** — `misc_hide_recents_desc` replaced in all three locales (labels untouched): `values/strings.xml:151`, `values-es/strings.xml:152`, `values-zh/strings.xml:152` (zh half-width punctuation, no trailing period). New copy states changing the setting closes the app and the icon may refresh briefly.
- **G3 (comment misstates sibling criterion)** — `RecentsHideHelper.kt:107-113`: rewritten to state the actual criterion — selection is by root component (package + launcher className suffix), and once a mismatch is detected every live launcher-rooted sibling is stale (each was launched under the other flag state); unrelated tasks (ToggleActivity's trampoline) are spared.
- **G4 (nullability inconsistency)** — `RecentsHideHelper.kt:144`: `Intent(activity.intent)` → `Intent(activity.intent ?: Intent())`; setComponent on the copy makes an empty base safe.
- **G5 (partial-failure window)** — `MiscellaneousSettingsActivity.kt:84-90`: catch block now runs `runCatching { RecentsHideHelper.sync(this) }.onFailure { Log.w("MiscellaneousSettingsActivity", "Post-rollback alias sync failed", it) }` after the pref/switch rollback and before the toast — alias state reconciles immediately instead of at next process start. Guarded because `sync()` calls `apply()` which throws by design plus binder calls.
- **G6 (spec appendix one commit stale)** — `docs/superpowers/specs/2026-09-02-hide-from-recents-design.md:158-160`: 遗留 line updated — relaunch-path exception guards landed in bfba7d4 with the narrowed sibling cleanup, and fix run 3 adds logging + rollback sync + copy disclosure. New paragraph appended on API-level posture: verification on API 35 only, `ensureExcluded` converges by design on all levels (relaunched intent always carries the constructed flag → at most one extra task create/remove per cold start), API ≤34 emulator smoke is a tracked follow-up before next release.

### Verification

Build: `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 8s` (only pre-existing `taskInfo.id` deprecation warnings at RecentsHideHelper.kt:101/118/157; mirrors.gradle moved aside and restored around the run). Emulator `dylike_test` (API 35) booted with `ANDROID_AVD_HOME=C:\Users\jiangyunfei\.android\avd`, APK installed, overlay via appops, a11y via `settings put secure`.

- **S1 PASS (fresh state)** — pm clear → monkey launch → SetupActivity (overlay + a11y pre-granted) → Continue → MainActivity → Home → Recents: **card ABSENT**. Oracles: overview uiautomator dump has 0 "Smart Edge" texts; `dumpsys activity recents` has 0 smartedge RecentTaskInfo entries (task 51 exists as TaskRecord rooted `.LauncherHidden flg=0x18800000` with no rendered card). Screenshots: `fix3-shot-s1-1-setup.png`, `fix3-shot-s1-2-after-continue.png`, `fix3-shot-s1-3-recents.png`.
- **S2 PASS (toggle cycle)** — Misc settings shows the new English desc verbatim (`fix3-shot-s2-1-misc-desc.png`). Toggle OFF → task torn down, fell to launcher as designed → relaunch → Recents **card PRESENT** (overview shows "Smart Edge"; dumpsys realActivity entry, `fix3-shot-s2-2-off-recents.png`); switch persisted OFF across relaunch. Toggle ON → fell to launcher → relaunch → Recents **card ABSENT** (0 texts, 0 RecentTaskInfo entries, `fix3-shot-s2-3-on-recents.png`); switch persisted ON across relaunch.
- **S3 PASS (logcat)** — `logcat -d | grep -E "FATAL|AndroidRuntime" | grep -i smartedge` → empty (0 FATAL EXCEPTION lines in the whole buffer); `grep RecentsHideHelper` → empty — no unexpected warnings during normal operation; guards only log on failure. No W/E lines tagged RecentsHideHelper/MiscellaneousSettingsActivity. Full buffer: `.superpowers/sdd/fix3-logcat.txt`.

Environment note: uiautomator dumps from Git Bash needed `adb shell "uiautomator dump ... && cat ..."` (single-quoted shell command) to avoid MSYS path mangling of /sdcard; Recents screenshots taken 7 s after APP_SWITCH to let the settled state re-bind (fix run 1 note). Emulator shut down via `adb emu kill` after the run.
