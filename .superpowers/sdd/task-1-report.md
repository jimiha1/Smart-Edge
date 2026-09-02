# Task 1 Report: Manifest — dual launcher aliases + service hardening

## What Was Implemented

Both manifest changes from the brief, applied verbatim to
`D:\Smart-Edge\app\src\main\AndroidManifest.xml`:

1. **Step 1 — MainActivity replaced with dual aliases.** The original
   `<activity android:name=".MainActivity" android:exported="true">` block (with its
   LAUNCHER intent-filter and `@xml/shortcuts` meta-data) was replaced with:
   - `.MainActivity` as `android:exported="false"` with no intent-filter, preceded by the
     brief's explanatory comment.
   - `<activity-alias android:name=".LauncherHidden">` — `exported="true"`,
     `enabled="true"`, `excludeFromRecents="true"`, MAIN/LAUNCHER intent-filter,
     `@xml/shortcuts` meta-data (the default entry, matching hideFromRecents default = true).
   - `<activity-alias android:name=".LauncherVisible">` — `exported="true"`,
     `enabled="false"`, MAIN/LAUNCHER intent-filter, `@xml/shortcuts` meta-data
     (alternate entry for when the user disables "Hide from Recents").
   Comments included exactly as in the brief. Alias names `LauncherHidden` / `LauncherVisible`
   match what Task 2's `RecentsHideHelper` will reference.

2. **Step 2 — FloatingPanelService hardened.** `android:stopWithTask="false"` added
   between `android:exported` and `android:foregroundServiceType` in the
   `.FloatingPanelService` declaration, exactly as the brief's snippet. The
   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property and everything else in the service block
   are untouched.

No other manifest content was modified — all `tools:ignore` annotations
(`QueryAllPackagesPermission`, `ProtectedPermissions`), the `<queries>` block, and every
other component declaration are byte-identical to before.

## Verification

Command: `./gradlew assembleDebug` from `D:\Smart-Edge` (Git Bash, JDK 17).

Result line:

```
BUILD SUCCESSFUL in 1m 41s
36 actionable tasks: 36 executed
```

Additional check beyond the brief: inspected the merged manifest at
`app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml` —
it contains `com.imi.smartedge.sidebar.panel.LauncherHidden` with
`enabled="true"` / `excludeFromRecents="true"` / `exported="true"` and the MAIN/LAUNCHER
intent-filter, `com.imi.smartedge.sidebar.panel.LauncherVisible`, and
`android:stopWithTask="false"` on the service. Merging behaved as expected.

## Commit

- `991ef99` — `feat(recents): hide app task from Recents via dual launcher aliases`
- 1 file changed, 28 insertions(+), 2 deletions(-). Only `app/src/main/AndroidManifest.xml`
  was staged; a pre-existing unrelated `M AGENTS.md` working-tree change was left untouched.

## Files Changed

- `D:\Smart-Edge\app\src\main\AndroidManifest.xml` (committed)
- `D:\Smart-Edge\local.properties` (created, gitignored — build-environment fix, see below)

## Environment Issues Encountered and Fixed (not caused by the manifest change)

Three machine-level problems blocked the build before verification could succeed:

1. **User init-script conflict (will recur).** `C:\Users\jiangyunfei\.gradle\init.d\mirrors.gradle`
   injects Maven repositories via `allprojects { repositories { ... } }`, which aborts any
   build of this project because `settings.gradle.kts` sets
   `RepositoriesMode.FAIL_ON_PROJECT_REPOS`:
   `repository 'Google' was added by initialization script`.
   Workaround used: temporarily moved the init script to `/tmp`, built, restored it
   afterwards (verified restored). **Task 2-4 implementers will hit this on every
   `./gradlew` invocation** until the init script is rewritten to hook
   `settingsEvaluated { it.dependencyResolutionManagement.repositories { ... } }` instead of
   `allprojects`, or is moved aside.
2. **Missing SDK location.** No `local.properties` and no `ANDROID_HOME`. Found a full SDK
   at `C:\Android` and created gitignored `local.properties` with `sdk.dir=C\:\\Android`.
3. **Missing platform android-34.** The SDK only had android-35/36. AGP auto-downloaded and
   installed `platforms/android-34` (revision 3) into `C:\Android` on the first build
   attempt (license already accepted); the build succeeded on the second run. This is
   persisted, so later tasks won't re-download.

## Self-Review Findings

- Diffed the commit against the brief's XML blocks character-for-character: both alias
  blocks, comments, attribute names/values/order, and the `stopWithTask` insertion match
  the brief verbatim.
- Nothing extra was added or removed; no other manifest elements were touched.
- Alias component names are exactly `LauncherHidden` / `LauncherVisible` as later tasks
  require.
- Commit message and staged file set match the brief's Step 4 exactly.

## Concerns

- The `mirrors.gradle` init-script conflict (item 1 above) is a recurring environment
  hazard for subsequent tasks on this machine; flagged so the coordinator can include the
  workaround in Task 2-4 briefs or have the init script fixed permanently.
- Otherwise none: no code changes were needed, and the manifest merger accepted the
  aliases without warnings.
