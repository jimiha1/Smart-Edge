package com.imi.smartedge.sidebar.panel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
     * foreground service is not restarted by the switch. The incoming alias
     * is enabled before the outgoing one is disabled, so there is never a
     * window with zero enabled launcher entries. Throws if the underlying
     * PackageManager call fails — callers handle rollback.
     */
    fun apply(context: Context, hidden: Boolean) {
        val pm = context.packageManager
        val (incoming, outgoing) =
            if (hidden) hiddenAlias(context) to visibleAlias(context)
            else visibleAlias(context) to hiddenAlias(context)
        pm.setComponentEnabledSetting(
            incoming,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            outgoing,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /**
     * Reconciles alias component state with the hideFromRecents preference.
     * Covers drift such as component state surviving an app-data clear, or a
     * partially-applied switch. Safe to call on every app start; it is a no-op
     * when the states already agree (a fresh install's DEFAULT states are
     * pinned to explicit states on the first sync).
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

    /**
     * Some Android versions (verified on API 35) ignore
     * android:excludeFromRecents declared on an activity-alias, so exclusion
     * is additionally enforced with the runtime intent flag: whenever the
     * task's base intent does not match the hideFromRecents preference, the
     * activity re-launches itself into a fresh task whose base intent carries
     * FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS (or drops it when showing again).
     * Returns true when the caller was re-launched and must return from
     * onCreate immediately.
     *
     * API 35 implementation notes (all verified on emulator):
     * - A CLEAR_TASK relaunch reuses the existing task without restamping
     *   its base intent, so the flag never takes effect; MULTIPLE_TASK is
     *   required to force a fresh task that adopts the flag.
     * - A plain finish() leaves a recents tombstone for the original
     *   launcher task that still renders a card, so the old task is removed
     *   via AppTask.finishAndRemoveTask().
     * - After the preference is toggled inside a live task the launcher
     *   stacks the new root on the stale task, so the mismatch check reads
     *   the task's base intent (not just this activity's intent) and stale
     *   sibling tasks are removed to leave exactly one correctly-flagged
     *   task.
     * - The fresh task is rooted at the enabled launcher alias so that
     *   toggling the preference kills the stale task (the platform removes
     *   tasks whose root component was disabled), making the next launch
     *   rebuild the flag state from scratch.
     */
    fun ensureExcluded(activity: android.app.Activity): Boolean {
        val hidden = PanelPreferences(activity).hideFromRecents
        val am = activity.getSystemService(android.app.ActivityManager::class.java) ?: return false
        val currentTaskId = activity.taskId
        val taskExcluded = (
            am.appTasks.firstOrNull { it.taskInfo?.id == currentTaskId }
                ?.taskInfo?.baseIntent?.flags ?: 0
            ).and(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0
        val intentExcluded =
            (activity.intent?.flags ?: 0).and(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0
        if (hidden == (taskExcluded || intentExcluded)) return false
        // Drop sibling tasks rooted at this app's own launcher entries that
        // still carry the stale flag state; other tasks (e.g. the shortcut
        // trampoline) are left alone. Stale AppTask handles can throw once
        // their task is gone (e.g. during alias-disable teardown), so each
        // remote call is guarded.
        val launcherEntrySuffixes =
            listOf(".MainActivity", ".SetupActivity", ".LauncherHidden", ".LauncherVisible")
        am.appTasks
            .filter { it.taskInfo?.id != currentTaskId }
            .filter {
                val component = it.taskInfo?.baseIntent?.component
                component != null &&
                    component.packageName == activity.packageName &&
                    launcherEntrySuffixes.any { suffix -> component.className.endsWith(suffix) }
            }
            .forEach { runCatching { it.finishAndRemoveTask() } }
        // Root the fresh task at the launcher alias matching the preference:
        // disabling an alias kills tasks rooted at it, so toggling the
        // preference tears down the stale task and the next launch rebuilds
        // one with the right flag state. Falls back to the activity's real
        // class if the alias is not resolvable.
        val aliasComponent = if (hidden) hiddenAlias(activity) else visibleAlias(activity)
        val target = runCatching {
            activity.packageManager.getActivityInfo(aliasComponent, 0)
        }.getOrNull()?.let { aliasComponent } ?: ComponentName(activity, activity.javaClass)
        val relaunch = Intent(activity.intent).apply {
            setComponent(target)
            setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                (if (hidden) Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS else 0)
            )
        }
        val relaunched = runCatching { activity.startActivity(relaunch) }.isSuccess
        if (!relaunched) return false
        runCatching {
            am.appTasks.firstOrNull { it.taskInfo?.id == currentTaskId }?.finishAndRemoveTask()
        }
        activity.finish()
        return true
    }
}
