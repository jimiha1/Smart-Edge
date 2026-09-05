# Task 2 Report: IME detection fix + accessibility instrumentation

## What was implemented

All four code steps from `task-2-brief.md`, applied verbatim to
`app/src/main/java/com/imi/smartedge/sidebar/panel/PanelAccessibilityService.kt`:

1. **Enabled-IME package cache** — added `cachedImePackages` / `imePkgsCachedAt` /
   `enabledImePackages()` below the `lastImeVisible` declaration. Reads
   `Settings.Secure.ENABLED_INPUT_METHODS`, splits on `:`, strips `/componentId`,
   caches for 30s (or until non-empty refresh).
2. **setImeVisible + checkImeVisibilityFromEvent fix** — `setImeVisible` now takes
   `(visible: Boolean, by: String)` and emits `DebugLog.i(TAG, "ime ...")` plus the
   tagged logcat line. `checkImeVisibilityFromEvent` now treats a
   TYPE_WINDOW_STATE_CHANGED event whose package is ANY enabled IME's package as
   IME-open (falling back to the old "InputMethod" className heuristic), and closes
   on a non-IME event from another package. The "Open signal" comment line above
   `lastImeVisible` was updated exactly as specified.
3. **Foreground-tracking filter** — replaced the `DEFAULT_INPUT_METHOD` /
   `imePackage` / `isSystemPkg` block in `onAccessibilityEvent`'s
   TYPE_WINDOW_STATE_CHANGED branch with the `filter` when-classification
   (`"ime" | "system" | "own" | "app"`, in that order), the
   `DebugLog.i(TAG, "win pkg=... cls=... filter=..." [+ fgChange])` log, and
   `if (filter == "app") { panelPrefs.currentForegroundPackage = packageName }`.
   The close-panel gate below it now reads `if (filter == "app") {` with its body
   (ACTION_CLOSE_PANEL + ACTION_REFRESH when `panelPrefs.serviceEnabled`)
   unchanged. The unused `defaultIme` / `imePackage` locals were deleted.
4. **Launcher fallback instrumentation** — `onLauncherContentEvent` throttle block
   now computes `throttled` and logs
   `DebugLog.i(TAG, "launcherContent pkg=$pkg throttled=$throttled")` before the
   early return.

## Verification

Command: `./gradlew assembleDebug` from D:\Smart-Edge (after moving
`~/.gradle/init.d/mirrors.gradle` → `.bak` per the known workaround; restored
afterwards).

Result:

```
BUILD SUCCESSFUL in 2s
36 actionable tasks: 5 executed, 31 up-to-date
```

(`compileDebugKotlin` executed and passed — no leftover `imePackage` reference.)

## Files changed

- `app/src/main/java/com/imi/smartedge/sidebar/panel/PanelAccessibilityService.kt`
  (+61 / −23)

## Commit

- `dc52ff4` — `fix(ime): detect any enabled IME package, not just InputMethod
  class names` (only the one Kotlin file staged, exactly as the brief specifies;
  pre-existing modified `.superpowers/sdd/*` files were left unstaged).

## Self-review findings

- All four steps present; code matches the brief verbatim (including comment
  wording, log formats, and the 30s cache TTL).
- `setImeVisible` new signature `(visible: Boolean, by: String)` used at both call
  sites (`"imePkg"/"cls"` for open, `"nonImeEvent"` for close) — confirmed by grep,
  no other call sites exist.
- `filter` when-block order is `ime → system → own → app`, matching the brief.
- Grep confirms zero remaining references to `imePackage`, `defaultIme`, or
  `DEFAULT_INPUT_METHOD`.
- Nothing extra: every hunk in `git show dc52ff4` maps 1:1 to a brief step.
- The unused `import android.util.Log` at the top of the file predates this task
  (old code also used fully-qualified `android.util.Log.d`); left untouched since
  the brief doesn't mention it.
- Note: this file previously held a Task 2 report from an earlier plan run
  ("runtime switching engine"); overwritten per current instructions.

## Issues or concerns

- None blocking. Behavioral note: the close-panel/foreground gates now exclude the
  app's own package via `packageName == myPkg` (equivalent to the old hardcoded
  `"com.imi.smartedge.sidebar.panel"` string, but derived from context — same
  applicationId).
- Runtime verification on a real device with a third-party keyboard (wetype /
  Doubao) is still pending; that is the point of the debug instrumentation and
  out of scope for this task.

## Fix 1 (D1 SecurityException)

**Defect:** Emulator verification (API 35) found a crash loop —
`Settings.Secure.getString(ENABLED_INPUT_METHODS)` in `enabledImePackages()`
throws `java.lang.SecurityException` for apps targeting SDK 34+ ("only readable
to apps with targetSdkVersion lower than or equal to: 33"), killing the a11y
service on every TYPE_WINDOW_STATE_CHANGED event. Evidence:
`.superpowers/sdd/emu5-crash-trace.txt`.

**What changed** (commit `a7d2c9d`,
`fix(ime): read enabled IMEs via InputMethodManager (Settings.Secure blocked on API 34+)`,
same file as Task 2):

- `enabledImePackages()` body replaced: the set now comes from the public
  `InputMethodManager.enabledInputMethodList` API (via `getSystemService`), wrapped
  in `runCatching { ... }.getOrDefault(emptySet())`; the 30s cache logic is
  unchanged.
- **One-token deviation from the supplied snippet, required to compile:** the
  block value `imm?.enabledInputMethodList?.map{...}?.toSet()` is nullable
  (`Set<String!>?`), so `getOrDefault(emptySet())` alone still returned a nullable
  type and `compileDebugKotlin` failed (`Type mismatch: inferred type is
  Set<String!>? but Set<String> was expected`, line 102). Added `?: emptySet()`
  after `.toSet()` inside the block; semantics identical (null IMM → empty set).
- Comment block above the cache fields updated to state the truth: the set comes
  from `InputMethodManager.enabledInputMethodList` because
  `Settings.Secure.ENABLED_INPUT_METHODS` throws SecurityException for apps
  targeting SDK 34+ (verified as a crash loop on API 35 emulator).
- Grep confirms no code read of `ENABLED_INPUT_METHODS` or `DEFAULT_INPUT_METHOD`
  remains anywhere under `app/src/main/java/` — the only remaining occurrence of
  the string is the explanatory comment.

**Build result** (`./gradlew assembleDebug`, mirrors.gradle workaround applied
and restored):

```
BUILD SUCCESSFUL in 2s
36 actionable tasks: 5 executed, 31 up-to-date
```

## Fix 2 (final review J1-J4)

Whole-branch review findings applied in one commit — `df2a7b5`
`fix(ime): harden IME clear signal, tune log volume, amend spec for IMM substitution`
(code + spec doc together, 3 files, +15/−5):

- **J1 (spec amendment):** appended `## 附录：实现期发现（2026-09-05，模拟器验收后）`
  to `docs/superpowers/specs/2026-09-05-ime-detection-fix-and-debug-logging-design.md`,
  matching the hide-from-recents spec's appendix style. It documents (1) the IME
  package set now comes from `InputMethodManager.enabledInputMethodList` because
  `Settings.Secure.ENABLED_INPUT_METHODS` throws SecurityException for targetSdk
  34+ (empirically a crash loop on API 35, fixed in a7d2c9d), superseding the
  body text at「背景与问题」末条 and「IME 检测修复」第 1 条; and (2) J3's
  log-placement deviation (`refreshSent=true` only, not per-event `throttled`).
- **J2 (harden IME clear signal):** `checkImeVisibilityFromEvent`'s clear branch
  now also requires `pkg != "com.android.systemui" && pkg != "android"`, so
  SystemUI/system windows (volume dialog, shade) no longer clear IME-visible
  while the keyboard is still open. Added the rationale comment inline (keyboard
  close is always followed by an app/launcher window re-announcing, so ignoring
  system windows only delays the clear, never misses it).
- **J3 (log after throttle):** `onLauncherContentEvent` no longer logs every
  launcher content-changed event; it logs
  `launcherContent pkg=$pkg refreshSent=true` only after passing the 800ms
  throttle and sending the refresh. The `throttled` local was removed.
- **J4 (gate snapshot completeness):** `addEdgeHandle`'s
  `handle gates:` DebugLog line now starts with
  `onlyOnHome=${panelPrefs.onlyOnHome} `, making each gate line self-contained
  (the ACTION_REFRESH variant already had it).

**Build result** (`./gradlew assembleDebug`, mirrors.gradle workaround applied
and restored):

```
BUILD SUCCESSFUL in 3s
36 actionable tasks: 5 executed, 31 up-to-date
```

No concerns; all four findings were applied exactly as specified (J2's code is
verbatim plus the suggested rationale comment).
