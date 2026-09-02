package com.imi.smartedge.sidebar.panel

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches the manifest launcher entry between the LauncherHidden and
 * LauncherVisible activity-aliases so the app's task can be excluded from
 * Recents at runtime. This prevents the panel service from being
 * swipe-killed on OEM ROMs that terminate the process when a task card
 * is dismissed. Only one alias is enabled at a time; both share the
 * application icon/label, so the home-screen icon looks unchanged.
 */
object RecentsHideHelper {

    private fun hiddenAlias(context: Context) =
        ComponentName(context, "${context.packageName}.LauncherHidden")

    private fun visibleAlias(context: Context) =
        ComponentName(context, "${context.packageName}.LauncherVisible")

    /**
     * Enables exactly one of the two launcher aliases. Pass hidden = true to
     * exclude the app's task from Recents. Uses DONT_KILL_APP so the running
     * foreground service is not restarted by the switch.
     */
    fun apply(context: Context, hidden: Boolean) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            hiddenAlias(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            visibleAlias(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /**
     * Reconciles alias component state with the hideFromRecents preference.
     * Covers drift such as component state surviving an app-data clear, or a
     * partially-applied switch. Safe to call on every app start; it is a no-op
     * when the states already agree (DEFAULT state counts as the manifest
     * default, which matches the preference default).
     */
    fun sync(context: Context) {
        val hidden = PanelPreferences(context).hideFromRecents
        val pm = context.packageManager
        val wantHidden = if (hidden) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val wantVisible = if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val hiddenOk = pm.getComponentEnabledSetting(hiddenAlias(context)) == wantHidden
        val visibleOk = pm.getComponentEnabledSetting(visibleAlias(context)) == wantVisible
        if (!hiddenOk || !visibleOk) apply(context, hidden)
    }
}
