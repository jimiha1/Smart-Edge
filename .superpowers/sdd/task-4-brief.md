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
