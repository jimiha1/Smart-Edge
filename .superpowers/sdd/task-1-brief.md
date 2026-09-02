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

