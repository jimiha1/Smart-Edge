# AGENTS.md

## Project Overview

Smart Edge: Sidebar & Gestures — an OriginOS-inspired Android floating side-panel launcher. Kotlin, single Gradle module `:app`, namespace `com.imi.smartedge.sidebar.panel`. UI is Android Views + ViewBinding + Material 3 themes (no Compose). minSdk 26, targetSdk/compileSdk 34, AGP 8.3.2, Gradle 8.6, Kotlin 1.9.24. Distributed via GitHub Releases and F-Droid.

All Kotlin sources live flat in `app/src/main/java/com/imi/smartedge/sidebar/panel/` (no subpackages). Key layers:

- **Overlay/runtime**: `FloatingPanelService` (panel lifecycle + handle), `SidePanelView`, `EdgeHandleView`/`NotchHandleView`, `ActionDispatcher` (all handle/gesture actions route through this), plus `SidePanelApp` (Application), `BootReceiver` (restore on boot), `PanelTileService` (quick-settings tile toggle).
- **System integration**: `PanelAccessibilityService` (gesture close, system actions), `AutomationManager` + Shizuku deps (root/Shizuku gesture engine), `SplitScreenHelper` + `HiddenApiBypass` (freeform/split-screen windowing), `NotificationTrackingService` (Productivity Hub).
- **Data**: `AppRepository` (app list/pinning), `IconPackManager`, `AppIconModelLoader` (Glide + LruCache icon loading), `PanelPreferences` (all SharedPreferences access goes through this).
- **UI**: `MainActivity`/`SetupActivity` + one Activity per settings page (`SettingsMainActivity`, `InteractionSettingsActivity`, etc.).

## Build & Test Commands

JDK 17 required. From repo root (Git Bash on Windows):

```bash
./gradlew assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease      # requires keystore.properties at root (see below)
./gradlew lint                 # abortOnError = true; lint errors fail the build
./gradlew test                 # no test/androidTest source sets exist yet
```

`assembleRelease` needs `keystore.properties` at the repo root (gitignored; CI creates it from `RELEASE_*` secrets). Without it the build produces an unsigned release APK instead of failing. Release builds run R8 minify + resource shrinking; `proguard-rules.pro` keeps the entire app package, so new dependencies may need their own keep rules. CI (`.github/workflows/build.yml`) runs `assembleDebug` on push/PR.

## Release Flow

Releases are tag-driven (`.github/workflows/release.yml` on push of `v*` tags). When cutting a release, update all of these in sync:

1. `versionCode` / `versionName` in `app/build.gradle.kts`.
2. `CHANGELOG.md` — CI extracts the release body from the `### vX.Y.Z` heading down to the next `---` separator; the heading must exactly match the tag or the release notes come up empty.
3. `fastlane/metadata/android/en-US/` — descriptions and `changelogs/<versionCode>.txt` (F-Droid reads the changelog file named by versionCode).

Commit style follows conventional-ish prefixes seen in `CHANGELOG.md`: `feat(scope)`, `fix(scope)`, `refactor(scope)`, `chore`, `perf`.

**Fork note**: this repo is a fork of `Imtiaz-official/Smart-Edge`; `.github/workflows/sync-upstream.yml` auto-merges `upstream/main` into `main` every Monday 18:00 UTC. Keep commits conventional and avoid gratuitous rewrites of upstream files — large diffs will conflict with the weekly merge.

## Conventions & Gotchas

- **Localization**: `resConfigs("en", "es", "zh")` restricts shipped locales. Translations live in `values-es/strings.xml` (Spanish, incomplete — falls back to English per-string) and `values-zh/strings.xml` (Simplified Chinese, complete). New user-facing strings go in `values/strings.xml` (English base) and ideally both translations; adding a new language also requires updating `resConfigs`, the picker arrays in `MiscellaneousSettingsActivity`, and `updateLanguageLabel()`. In-app language switching is handled by `LocaleHelper` (persists the choice and wraps `attachBaseContext`).
- **Preferences**: never read `SharedPreferences` directly; add accessors to `PanelPreferences`.
- **Windowing/system APIs**: freeform, split-screen, and secure-settings operations rely on `HiddenApiBypass` and Shizuku/root fallbacks — behavior differs by Android version and engine availability; `FloatingPanelService` requires an engine check (Accessibility or Automation) before showing the handle.
- **Sensitive permissions** (`QUERY_ALL_PACKAGES`, `WRITE_SECURE_SETTINGS`) carry `tools:ignore` in the manifest — keep those annotations when editing the manifest, and keep dependencies F-Droid-compatible (no proprietary binaries).
- **Performance**: app-icon rendering is Glide-based with an LruCache and coroutine preloading; don't bypass `AppIconModelLoader` when displaying app icons.
- No DI framework, no Compose, no Flow. `kotlinx.coroutines` IS used (`CoroutineScope`/`Dispatchers`/`withContext` in `AppRepository`, `FloatingPanelService`, icon preloading) but is **not declared** in `libs.versions.toml` — it resolves only transitively, so changing dependencies can silently break compilation. Match the existing plain-Kotlin + ViewBinding + coroutine style.
