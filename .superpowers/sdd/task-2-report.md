# Task 2 Report: Runtime switching engine (PanelPreferences + RecentsHideHelper + app-start sync)

## What was implemented

All 6 steps of the brief, code verbatim:

1. **Step 1 — Preference constant**: Added `private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"` in the `PanelPreferences` companion object, immediately after `KEY_HIDE_NOTIFICATION` (line ~37).
2. **Step 2 — Property accessor**: Added `var hideFromRecents: Boolean` (default `true`) with the brief's KDoc, immediately after the existing `hideServiceNotification` property (line ~639). Follows the custom `get()/set()` on `prefs` convention.
3. **Step 3 — RecentsHideHelper.kt**: Created flat-package `object RecentsHideHelper` with `apply(context, hidden)` (flips both `LauncherHidden`/`LauncherVisible` aliases via `setComponentEnabledSetting` with `DONT_KILL_APP`) and `sync(context)` (reconciles alias state with `PanelPreferences.hideFromRecents`, no-op when states agree).
4. **Step 4 — App-start sync**: In `SidePanelApp.onCreate()`, added `RecentsHideHelper.sync(this)` immediately after `applyAppTheme(this)`, with the brief's comments.
5. **Step 5 — Build verification**: passed (below).
6. **Step 6 — Commit**: `4399cbe feat(recents): runtime alias switching engine (RecentsHideHelper)` with exactly the three files.

## Verification

Command: `./gradlew assembleDebug` from `D:\Smart-Edge` (after temporarily moving `~/.gradle/init.d/mirrors.gradle` aside per known-issue workaround; restored afterward).

Result:

```
BUILD SUCCESSFUL in 4s
36 actionable tasks: 5 executed, 31 up-to-date
```

(`:app:compileDebugKotlin` executed against the new/modified sources — the change was actually compiled.)

## Files changed

- `app/src/main/java/com/imi/smartedge/sidebar/panel/PanelPreferences.kt` — +10 lines (constant + property)
- `app/src/main/java/com/imi/smartedge/sidebar/panel/RecentsHideHelper.kt` — new file, 62 lines
- `app/src/main/java/com/imi/smartedge/sidebar/panel/SidePanelApp.kt` — +4 lines (sync call + comments)

## Self-review findings

- Diff reviewed via `git show 4399cbe`: all inserted code is byte-identical to the brief; insertion points landed exactly where specified (constant next to `KEY_HIDE_NOTIFICATION`; property right after `hideServiceNotification`; sync call right after `applyAppTheme(this)`).
- Verified `PanelPreferences(context)` constructor usage in `sync()` matches the existing pattern (class is `class PanelPreferences(context: Context)`).
- Verified alias component names resolve correctly: helper builds `context.packageName + ".LauncherHidden"/".LauncherVisible"` = `com.imi.smartedge.sidebar.panel.LauncherHidden`/`...LauncherVisible`, matching Task 1's manifest aliases.
- Nothing unrelated committed: pre-existing modified `AGENTS.md` and untracked `.superpowers/` directory were left out of the commit (commit stages only the three files per the brief).
- Interfaces for Task 3 produced as specified: `PanelPreferences.hideFromRecents: Boolean` (default `true`), `RecentsHideHelper.apply(context, hidden)`, `RecentsHideHelper.sync(context)`.

## Issues or concerns

- None blocking. Minor behavioral note (code is verbatim from the brief, so not a deviation): on a fresh install, `getComponentEnabledSetting` returns `COMPONENT_ENABLED_STATE_DEFAULT` (0) rather than `ENABLED` (1), so `sync()` will call `apply()` once on first launch to pin explicit component state even though the manifest default already matches. This is harmless and converges to explicit state — consistent with the KDoc's stated intent.
- The untracked `app/src/main/java/com/imi/smartedge/sidebar/panel/.superpowers/` directory inside the source tree predates this task and was not touched; it may be worth investigating separately (looks like tooling output accidentally created in the source dir).
