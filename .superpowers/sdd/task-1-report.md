# Task 1 Report: DebugLog component + preference

## Status: DONE

## What was implemented

Exactly per `task-1-brief.md`, all code used verbatim:

1. **Step 1 — Branch**: created `feat/ime-fix-debug-log` from `main` (cdb62ed).
2. **Step 2 — Preference** in `PanelPreferences.kt`:
   - Added `private const val KEY_DEBUG_LOG = "debug_log_enabled"` in the companion object, directly below `KEY_ONLY_ON_HOME` (line 88).
   - Added `var debugLogEnabled: Boolean` (get/set via `prefs`/`prefs.edit`, default `false`) with the brief's KDoc comment, immediately after the `onlyOnHome` property (line ~701).
3. **Step 3 — `DebugLog.kt`** created at `app/src/main/java/com/imi/smartedge/sidebar/panel/DebugLog.kt` with the brief's code byte-identical (verified via `diff` — zero differences). Provides:
   - `DebugLog.i(tag, msg)`
   - `DebugLog.e(tag, msg, tr: Throwable? = null)`
   - `DebugLog.session(context)` — starts HandlerThread + writes session header only when `debugLogEnabled` is on; calls `shutdown()` otherwise
   - `DebugLog.shutdown()`
   - `DebugLog.readAll(context): String?`
   - Private: `header()`, `enqueue()`, `append()` (2 MB rotation to `smartedge.log.1`), `stackToString()`
4. **Step 4 — Build** (with the known workaround: moved `~/.gradle/init.d/mirrors.gradle` → `.bak` before the run, restored after — verified restored).
5. **Step 5 — Commit**: staged only the two brief-specified files, committed with the exact message.

## Verification

Command: `./gradlew assembleDebug --console=plain` from `D:\Smart-Edge` (Git Bash, JDK 17; mirrors.gradle disabled per workaround, restored afterwards).

Result line:

```
BUILD SUCCESSFUL in 30s
36 actionable tasks: 5 executed, 31 up-to-date
```

One Kotlin warning during `:app:compileDebugKotlin` (warning only, not an error):

```
w: file:///D:/Smart-Edge/app/src/main/java/com/imi/smartedge/sidebar/panel/DebugLog.kt:88:37 'versionCode: Int' is deprecated. Deprecated in Java
```

This comes from the brief's verbatim `header()` code (`pi.versionCode` — deprecated since API 28 in favor of `longVersionCode`). Kept verbatim as required; harmless.

## Files changed

- `app/src/main/java/com/imi/smartedge/sidebar/panel/PanelPreferences.kt` (+6 lines)
- `app/src/main/java/com/imi/smartedge/sidebar/panel/DebugLog.kt` (new, 135 lines)

Commit: `ad4891a` — `feat(log): add rotating file logger (DebugLog) and preference` (2 files changed, 141 insertions(+), 0 deletions)

## Self-review findings

- All "Produces" interfaces exist with exact names/signatures: `DebugLog.i/e/session/shutdown/readAll`, `PanelPreferences.debugLogEnabled` (default `false`). Confirmed against the committed diff.
- `DebugLog.kt` is byte-identical to the brief's code block (checked with `diff`).
- Nothing extra added, no unrelated edits. The only other working-tree change, `.superpowers/sdd/task-1-brief.md`, was already modified before this task started and was left untouched/uncommitted.
- Codebase conventions respected: flat package, `PanelPreferences` accessor style matches neighboring properties (`prefs.edit { ... }` KTX pattern already imported in that file).
- Note: this file previously held a Task 1 report from an earlier plan (manifest dual-launcher-aliases); overwritten as instructed.

## Issues or concerns

- None blocking. Minor note for later tasks: the `versionCode` deprecation warning above could be silenced with `@Suppress("DEPRECATION")` or `longVersionCode`, but the brief mandates verbatim code so it was left as-is.

## Fix 1 (review F1-F3)

Applied on `feat/ime-fix-debug-log` to `app/src/main/java/com/imi/smartedge/sidebar/panel/DebugLog.kt`:

- **F1 (Important) + F2 (Minor) — `enqueue()` rewritten** with the reviewer's exact code: caller-thread timestamp capture (`System.currentTimeMillis()` → `at`) and caller-thread-id capture (`tid`) now happen before the post; `lineTs.format(Date(at))` runs inside the posted lambda, confining the non-thread-safe `SimpleDateFormat` to the DebugLog HandlerThread, and logged `T<id>` now attributes the real calling thread instead of the log thread.
- **F3 (Minor) — `header()` versionCode**: replaced `${pi.versionCode}` with the reviewer's SDK-28-guarded expression (`pi.longVersionCode` on API >= 28, `pi.versionCode.toLong()` below). Note: the guarded fallback branch still references the deprecated field, so the `w: 'versionCode: Int' is deprecated` warning persisted after the replacement alone. Added `@Suppress("DEPRECATION")` (with a guard-explaining comment) on `header()` to actually eliminate the warning — the reviewer's stated goal — without changing behavior.

Verification: `./gradlew assembleDebug --console=plain` (mirrors.gradle move-aside workaround applied and restored):

```
BUILD SUCCESSFUL in 2s
```

No `w:` deprecation warnings emitted — the previous `DebugLog.kt:88 'versionCode: Int' is deprecated` warning is gone.

Commit: `defb2e6` — `fix(log): confine date formatting to log thread and attribute caller thread` (1 file changed, 5 insertions(+), 3 deletions(-); only DebugLog.kt staged).
