### Task 3: Settings UI — toggle in Miscellaneous settings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (after line 149)
- Modify: `app/src/main/res/values-es/strings.xml` (after line 150)
- Modify: `app/src/main/res/values-zh/strings.xml` (after line 150)
- Modify: `app/src/main/res/layout/activity_settings_misc.xml` (after the `misc_hide_notification_desc` TextView)
- Modify: `app/src/main/java/com/imi/smartedge/sidebar/panel/MiscellaneousSettingsActivity.kt` (after the `featureHideNotification` listener block, lines 58–65)

**Interfaces:**
- Consumes: `PanelPreferences.hideFromRecents` (Task 2), `RecentsHideHelper.apply(context, hidden)` (Task 2), ViewBinding id `feature_hide_recents` → `binding.featureHideRecents`.
- Produces: nothing consumed later.

- [ ] **Step 1: Add strings in all three locales**

`values/strings.xml` (after `misc_hide_notification_desc`):

```xml
    <string name="misc_hide_recents_label">Hide from Recents</string>
    <string name="misc_hide_recents_desc">Keeps the panel alive by hiding this app from Recents; takes effect on next launch. The home-screen icon may briefly refresh when toggled</string>
```

`values-zh/strings.xml`:

```xml
    <string name="misc_hide_recents_label">从最近任务隐藏</string>
    <string name="misc_hide_recents_desc">将应用从最近任务中隐藏,防止清理后台时误杀侧边栏;对新启动生效。切换开关时桌面图标可能短暂刷新,属正常现象</string>
```

`values-es/strings.xml`:

```xml
    <string name="misc_hide_recents_label">Ocultar de apps recientes</string>
    <string name="misc_hide_recents_desc">Oculta la app de las apps recientes para evitar que el panel se cierre al limpiar la memoria; surte efecto en el próximo inicio. El icono puede actualizarse brevemente al cambiarlo</string>
```

- [ ] **Step 2: Add the switch to the layout**

In `activity_settings_misc.xml`, inside the same card as `feature_hide_notification`, directly after the `misc_hide_notification_desc` TextView:

```xml
                    <!-- Hide from Recents -->
                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/feature_hide_recents"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/misc_hide_recents_label"
                        android:textColor="?attr/colorOnSurface"
                        android:textSize="16sp"
                        android:paddingVertical="12dp" />

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/misc_hide_recents_desc"
                        android:textColor="?attr/colorOnSurfaceVariant"
                        android:textSize="14sp"
                        android:layout_marginBottom="8dp" />
```

(Structure and attributes mirror the neighboring `feature_hide_notification` block exactly.)

- [ ] **Step 3: Wire up the activity**

In `MiscellaneousSettingsActivity.onCreate()`, after the existing `featureHideNotification` listener block (lines 58–65), add:

```kotlin
        val hideRecentsListener = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            val previous = panelPrefs.hideFromRecents
            try {
                RecentsHideHelper.apply(this, isChecked)
                panelPrefs.hideFromRecents = isChecked
            } catch (e: Exception) {
                // Roll the preference and the switch back on ROMs where component
                // switching fails; re-checking fires the listener again, so detach it first.
                panelPrefs.hideFromRecents = previous
                binding.featureHideRecents.setOnCheckedChangeListener(null)
                binding.featureHideRecents.isChecked = previous
                binding.featureHideRecents.setOnCheckedChangeListener(hideRecentsListener)
                binding.root.showModernToast("Couldn't change Recents visibility: ${e.message}")
            }
        }
        binding.featureHideRecents.isChecked = panelPrefs.hideFromRecents
        binding.featureHideRecents.setOnCheckedChangeListener(hideRecentsListener)
```

Note the listener is attached **after** the initial `isChecked` assignment so the programmatic set does not trigger it.

- [ ] **Step 4: Build and lint to verify**

Run: `./gradlew assembleDebug lint`
Expected: `BUILD SUCCESSFUL` for both. Lint failures about missing translations or unused resources must be fixed before committing (`abortOnError = true`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/res/layout/activity_settings_misc.xml \
        app/src/main/java/com/imi/smartedge/sidebar/panel/MiscellaneousSettingsActivity.kt
git commit -m "feat(recents): add hide-from-Recents toggle in misc settings"
```

---

