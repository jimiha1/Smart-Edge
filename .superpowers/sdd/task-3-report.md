# Task 3 Report: Settings UI — hide-from-Recents toggle in Miscellaneous settings

## What was implemented

All five files from the brief, with the exact strings/XML/Kotlin it specifies:

1. **`app/src/main/res/values/strings.xml`** — added `misc_hide_recents_label` ("Hide from Recents") and `misc_hide_recents_desc` verbatim, immediately after `misc_hide_notification_desc`.
2. **`app/src/main/res/values-es/strings.xml`** — Spanish translations verbatim from the brief, same position.
3. **`app/src/main/res/values-zh/strings.xml`** — Chinese translations verbatim from the brief, same position.
4. **`app/src/main/res/layout/activity_settings_misc.xml`** — added `feature_hide_recents` MaterialSwitch + desc TextView directly after the `misc_hide_notification_desc` TextView, inside the same General card, mirroring the `feature_hide_notification` block's structure/attributes exactly (XML verbatim from the brief).
5. **`app/src/main/java/com/imi/smartedge/sidebar/panel/MiscellaneousSettingsActivity.kt`** — wired the toggle in `onCreate()` after the `featureHideNotification` block: listener calls `RecentsHideHelper.apply(this, isChecked)` inside try/catch (load-bearing: `apply` throws on PackageManager failure), persists `panelPrefs.hideFromRecents` on success; on failure rolls the pref back, detaches the listener, resets `isChecked`, re-attaches, and toasts. Initial `isChecked = panelPrefs.hideFromRecents` is assigned **before** `setOnCheckedChangeListener` so the programmatic set does not fire the listener.

**One necessary deviation from the brief's verbatim Kotlin** (flagged as a concern below): the brief declared the listener as `val hideRecentsListener = ... { ... }` while also referencing `hideRecentsListener` inside the catch block to re-attach it. Kotlin local vals are not in scope within their own initializer lambda, so this fails to compile:

```
e: MiscellaneousSettingsActivity.kt:78:71 Unresolved reference: hideRecentsListener
```

Minimal fix applied: two-step nullable-var assignment (`var hideRecentsListener: ...OnCheckedChangeListener? = null` then assignment), with a two-line comment explaining why. The listener body, ordering, rollback semantics, and attach-after-initial-assignment behavior are unchanged from the brief. `setOnCheckedChangeListener`'s platform-type parameter accepts the nullable reference.

## Verification

Command: `./gradlew assembleDebug lint` from `D:\Smart-Edge` (JDK 17, Git Bash), with the known `~/.gradle/init.d/mirrors.gradle` init-script workaround (moved aside for the run, restored after every run).

- **assembleDebug**: passed. Quoted line: `BUILD SUCCESSFUL in 1s` (standalone re-run; in the combined `assembleDebug lint` run the `:app:assembleDebug` task completed successfully before lint failed).
- **lint (lintDebug)**: `BUILD FAILED` — "Lint found 55 errors, 658 warnings". **All 55 errors are pre-existing on HEAD of this branch and unrelated to this task.** Evidence (controlled stash test):
  - Stashed the 5 changed files, ran `lintDebug` on HEAD → 55 errors, BUILD FAILED.
  - Restored changes, re-ran → 55 errors, BUILD FAILED.
  - Normalized diff of both error lists (`sed 's/:[0-9]*:/:/' | sort | diff`) → **empty; identical error sets**. Warning sets (658 each) also identical.
  - No error references `misc_hide_recents*`, `activity_settings_misc.xml`, or `MiscellaneousSettingsActivity.kt`. Pre-existing errors are things like `UseAppTint` in unrelated layouts, `MissingPermission` in `PanelTileService.kt`, `RestrictedApi` in `Extensions.kt`, and `MissingTranslation` for ~25 long-untranslated Spanish strings (`section_panel_experience`, `feature_landscape_*`, etc.) that predate this branch's tasks.
  - My two new strings are present in all three locales (no MissingTranslation) and referenced by the layout (no UnusedResources) — the lint failure classes the brief warned about do not occur for this change.

Lint was therefore already failing on this branch before Task 3; the brief's expectation of "BUILD SUCCESSFUL for both" does not match the branch's actual state. Since fixing 55 pre-existing repo-wide lint errors (many requiring layout attribute rewrites or translating 25 strings) is far outside this task's scope and would contaminate the commit, I committed with zero-new-lint-issues evidence instead.

## Files changed (commit `8dddf18`)

- `D:\Smart-Edge\app\src\main\res\values\strings.xml`
- `D:\Smart-Edge\app\src\main\res\values-es\strings.xml`
- `D:\Smart-Edge\app\src\main\res\values-zh\strings.xml`
- `D:\Smart-Edge\app\src\main\res\layout\activity_settings_misc.xml`
- `D:\Smart-Edge\app\src\main\java\com\imi\smartedge\sidebar\panel\MiscellaneousSettingsActivity.kt`

Commit: `8dddf18` `feat(recents): add hide-from-Recents toggle in misc settings` — 5 files, 45 insertions, exactly the files listed in the brief's commit step.

## Self-review

- Completeness vs brief: all 5 steps done; strings verbatim in all three locales; XML verbatim and structurally mirroring the `featureHideNotification` block; Kotlin wired at the specified insertion point with attach-after-initial-`isChecked` ordering. Nothing extra added.
- Interfaces consumed as specified: `PanelPreferences.hideFromRecents` (verified at `PanelPreferences.kt:644`), `RecentsHideHelper.apply(context, hidden)` (verified at `RecentsHideHelper.kt:28`), ViewBinding `binding.featureHideRecents` resolves (compilation proves it).
- Rollback path re-attaches the same listener instance after resetting `isChecked`, so subsequent user toggles keep working after a failure.
- Unrelated pre-existing working-tree state (`AGENTS.md` modification, untracked `app/src/main/java/com/imi/smartedge/sidebar/panel/.superpowers/`) was left out of the commit intentionally.

## Issues / concerns

1. **Brief's Kotlin snippet does not compile as written** — self-referencing local `val` inside its own initializer lambda (`Unresolved reference`). Deviation documented above; behavior identical to the brief's intent.
2. **`lint` cannot reach BUILD SUCCESSFUL on this branch even without this change** (55 pre-existing errors, `abortOnError = true`). Earlier tasks on this branch could not have passed a full `./gradlew lint` either. If the team wants green lint, that needs a dedicated cleanup task (or a lint baseline).
3. Minor: the failure toast string "Couldn't change Recents visibility: ..." is hardcoded English in Kotlin, matching the file's existing hardcoded-toast style (`showModernToast` calls in this activity are all hardcoded English) — consistent with the codebase, noted for completeness.
