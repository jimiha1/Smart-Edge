Task 1: complete (commits bad4e07..991ef99, review clean; minor: lint deferred to Task 3 per plan)
Task 2: complete (commits 991ef99..4399cbe, review clean)
  Minor findings (for final review triage):
  - RecentsHideHelper.sync() KDoc overclaims fresh-install no-op (DEFAULT != ENABLED, pins explicit state once) — one-line doc fix
  - apply() disables hidden alias before enabling visible: theoretical no-launcher window; enable-before-disable shrinks it
  - apply() KDoc lacks throw-contract note
Task 3: complete (commits 4399cbe..8dddf18, review clean; deviation: listener val→nullable var compile fix, semantics verified)
  Minor findings (for final review triage):
  - failure toast hardcoded English (matches file-wide style; brief-verbatim) — future localization pass
  - catch path: throw after successful apply() rolls back pref only, not components (theoretical; brief ordering)
  - zh desc has mid-string full-width 。 (plan's own text; half-width rule otherwise met)
  - nullable listener var passed to platform-type setter (cosmetic)

## Handoff (2026-09-02, session end — resume on another machine)

State: Tasks 1-3 complete + task-reviewed clean. Task 4 acceptance found D1 (alias excludeFromRecents ignored on API 35) + D2 (SetupActivity re-root); fix run 1 (commit d0e4a10) passed V1-V5 on emulator; fix review APPROVED with findings open.

Remaining work (in order):
1. IMPORTANT fix: RecentsHideHelper relaunch path — wrap the two finishAndRemoveTask() calls and startActivity(relaunch) in runCatching (same pattern as the alias probe); stale AppTask handles can throw during alias-disable teardown and would crash in onCreate. Rebuild + quick emulator smoke (toggle cycle).
2. Optional minors: sibling cleanup filter is id-only (kills all app tasks incl. ToggleActivity's trampoline task — filter to flag-mismatched); null-polarity on ?.flags?.and() != 0 (add ?: 0); comment the early-return lifecycle dependency at the two call sites.
3. Final whole-branch review: use requesting-code-review code-reviewer template on merge-base bad4e07..HEAD; hand it the Minor findings recorded per-task above.
4. finishing-a-development-branch: merge feat/hide-from-recents to main (or PR).
5. Do NOT block on `./gradlew lint`: 55 pre-existing errors (old es MissingTranslation, UseAppTint, RestrictedApi) fail lint on main already — separate cleanup, out of feature scope.

Environment notes for the other machine:
- ~/.gradle/init.d/mirrors.gradle conflicts with FAIL_ON_PROJECT_REPOS: move aside before any ./gradlew run, restore after.
- Emulator: ANDROID_HOME=C:\Android, AVD dylike_test, adb at C:\Android\adb.exe; boot `emulator.exe -avd dylike_test -no-snapshot-save`, MCP android tools unusable (no ANDROID_HOME in their env) — drive via adb.
- git dubious-ownership was fixed via global safe.directory on THIS machine; the other machine may need `git config --global --add safe.directory <path>` again.
- Screenshots (*.png) were intentionally NOT committed (15MB); evidence text (dumpsys excerpts, PASS/FAIL) is in the reports.
Fix run 2: complete (commit bfba7d4, review clean; F1-F7 all approved)
  New minors (final-review triage): silent exception swallowing in runCatching (add Log.w), comment overstates flag-state selection
Fix run 3: complete (commit 6d691fd, re-review approved G1-G6; final review conditions satisfied)
Final whole-branch review: 'merge with fixes' -> conditions met by fix run 3
RIDE-list (documented, not blocking): hardcoded en toast (file-wide style), pref-write rollback asymmetry, nullable listener var, Recents 1-3s flash (platform), mid-toggle task kill (platform, now disclosed in copy)
Follow-up before next release: API<=34 emulator smoke test; lint baseline cleanup (55 pre-existing errors)
