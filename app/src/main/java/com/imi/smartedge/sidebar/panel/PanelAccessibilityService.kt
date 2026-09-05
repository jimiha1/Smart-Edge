package com.imi.smartedge.sidebar.panel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log

class PanelAccessibilityService : AccessibilityService() {

    private lateinit var panelPrefs: PanelPreferences
    private var lastImmersiveState = false
    private var lastPackageName: String? = null

    override fun onCreate() {
        super.onCreate()
        panelPrefs = PanelPreferences(this)
    }

    private fun checkImmersiveMode() {
        if (!panelPrefs.serviceEnabled) return
        val root = rootInActiveWindow ?: return
        
        // Strategy: Check if the main window covers the whole screen area
        // and doesn't have system bars visible. Since we can't directly check system bar visibility
        // easily from here, we look at the window bounds vs screen bounds.
        
        val displayMetrics = resources.displayMetrics
        val screenRect = android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        
        val windowRect = android.graphics.Rect()
        root.getBoundsInScreen(windowRect)
        
        // Many immersive apps (videos/games) have bounds that match screen closely.
        // We use a small tolerance (5%) to account for notches, display cutouts, 
        // or system-level padding that might report a slightly smaller root window.
        val isImmersive = windowRect.width() >= screenRect.width() * 0.95 && 
                         windowRect.height() >= screenRect.height() * 0.95
        
        if (isImmersive != lastImmersiveState) {
            lastImmersiveState = isImmersive
            val intent = Intent(this, FloatingPanelService::class.java).apply {
                action = FloatingPanelService.ACTION_UPDATE_IMMERSIVE
                putExtra("is_immersive", isImmersive)
            }
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "PanelAccessibility"
        const val ACTION_TAKE_SCREENSHOT = "com.imi.smartedge.sidebar.panel.ACTION_TAKE_SCREENSHOT"
        const val ACTION_SHOW_POWER_MENU = "com.imi.smartedge.sidebar.panel.ACTION_SHOW_POWER_MENU"
        const val ACTION_SPLIT_SCREEN = "com.imi.smartedge.sidebar.panel.ACTION_SPLIT_SCREEN"
        const val ACTION_TRIGGER_SHORTCUT = "com.imi.smartedge.sidebar.panel.ACTION_TRIGGER_SHORTCUT"
        const val ACTION_ONE_HANDED = "com.imi.smartedge.sidebar.panel.ACTION_ONE_HANDED"
        const val ACTION_PREVIOUS_APP = "com.imi.smartedge.sidebar.panel.ACTION_PREVIOUS_APP"
        const val ACTION_BACK = "com.imi.smartedge.sidebar.panel.ACTION_BACK"
        const val ACTION_HOME = "com.imi.smartedge.sidebar.panel.ACTION_HOME"
        const val ACTION_RECENTS = "com.imi.smartedge.sidebar.panel.ACTION_RECENTS"
        const val ACTION_NOTIFICATIONS = "com.imi.smartedge.sidebar.panel.ACTION_NOTIFICATIONS"
        const val ACTION_QUICK_SETTINGS = "com.imi.smartedge.sidebar.panel.ACTION_QUICK_SETTINGS"
        const val ACTION_LOCK_SCREEN = "com.imi.smartedge.sidebar.panel.ACTION_LOCK_SCREEN"
        const val ACTION_IME_STATE = "com.imi.smartedge.sidebar.panel.ACTION_IME_STATE"

        const val EXTRA_PKG = "pkg"
        const val EXTRA_MODE = "mode"

        @Volatile
        var isRunning = false
            private set
    }

    // --- IME visibility tracking ---
    // The edge handle is an overlay that sits above the keyboard and swallows
    // touches on the keys it covers, so it must be hidden while an IME shows.
    // Open signal: TYPE_WINDOW_STATE_CHANGED from the IME window — matched by event package (any enabled IME) or className containing "InputMethod".
    // Close signal: the next non-IME window event — when a keyboard closes, another
    // window always comes to front (verified on API 35: the launcher re-announces itself).
    private var lastImeVisible = false

    // --- Enabled-IME package cache ---
    // Third-party keyboards (WeChat wetype, Doubao, Baidu…) post window events
    // whose className does NOT contain "InputMethod" (their services are named
    // e.g. .ImeService / .WxHldService), so detection primarily matches the
    // event package against ALL enabled IME packages. Verified on-device
    // 2026-09-05: none of the user's IMEs match the old className heuristic.
    // The set comes from InputMethodManager.enabledInputMethodList: reading
    // Settings.Secure.ENABLED_INPUT_METHODS throws SecurityException for apps
    // targeting SDK 34+ (verified as a crash loop on an API 35 emulator).
    private var cachedImePackages: Set<String> = emptySet()
    private var imePkgsCachedAt = 0L

    private fun enabledImePackages(): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedImePackages.isEmpty() || now - imePkgsCachedAt > 30_000) {
            imePkgsCachedAt = now
            cachedImePackages = runCatching {
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.enabledInputMethodList
                    ?.map { it.packageName }
                    ?.toSet()
                    ?: emptySet()
            }.getOrDefault(emptySet())
        }
        return cachedImePackages
    }

    private fun setImeVisible(visible: Boolean, by: String) {
        if (visible == lastImeVisible) return
        lastImeVisible = visible
        DebugLog.i(TAG, "ime ${!visible}->$visible by=$by")
        android.util.Log.d(TAG, "IME visibility: $visible ($by)")
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = ACTION_IME_STATE
            putExtra("visible", visible)
        }
        startService(intent)
    }

    private fun checkImeVisibilityFromEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val className = event.className?.toString() ?: ""
        // Authoritative signal: the IME window's presence in the accessibility
        // window list (needs flagRetrieveInteractiveWindows). Event-based
        // clearing ("any non-IME window event means the keyboard closed")
        // proved unreliable — while the keyboard is open the launcher itself
        // posts window noise (e.g. a ListView), which wrongly cleared the
        // state and re-showed the handle over the keyboard (verified
        // 2026-09-05 17:56:57, exported log). Events only trigger the check;
        // the window list decides.
        val imeInWindows = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val byImePackage = pkg != null && pkg in enabledImePackages()
        when {
            imeInWindows || byImePackage || className.contains("InputMethod", ignoreCase = true) ->
                setImeVisible(true, when {
                    byImePackage -> "imePkg"
                    imeInWindows -> "windows"
                    else -> "cls"
                })
            // Clear only on the authoritative list. If the list is unusable
            // (empty — window retrieval unavailable on this ROM), fall back to
            // the old heuristic, minus the known noise sources.
            windows.isNotEmpty() -> setImeVisible(false, "windowsGone")
            else -> if (lastImeVisible && pkg != packageName &&
                pkg != "com.android.systemui" && pkg != "android"
            ) setImeVisible(false, "nonImeEvent")
        }
    }

    // --- Launcher redraw fallback ---
    // Some OEM launchers (e.g. MagicOS) do not reliably post a WINDOW_STATE_CHANGED
    // event when returning home, leaving the onlyOnHome handle hidden. Launcher
    // content changes (icon/widget redraw) fire right after it becomes visible,
    // so they serve as a backup "we are on home" signal.
    private var cachedLauncherPkgs: Set<String> = emptySet()
    private var launcherPkgsCachedAt = 0L
    private var lastLauncherContentRefresh = 0L

    private fun onLauncherContentEvent(pkg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - launcherPkgsCachedAt > 30_000) {
            launcherPkgsCachedAt = now
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            cachedLauncherPkgs = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }
                .toSet()
        }
        if (pkg !in cachedLauncherPkgs) return
        if (now - lastLauncherContentRefresh < 800) return
        lastLauncherContentRefresh = now
        DebugLog.i(TAG, "launcherContent pkg=$pkg refreshSent=true")
        val refresh = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        startService(refresh)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TAKE_SCREENSHOT -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
            }
            ACTION_SHOW_POWER_MENU -> {
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            }
            ACTION_SPLIT_SCREEN -> {
                val pkg = intent.getStringExtra(EXTRA_PKG)
                val mode = intent.getIntExtra(EXTRA_MODE, 1)
                if (pkg != null) {
                    if (mode == SplitScreenHelper.MODE_TOP || mode == SplitScreenHelper.MODE_BOTTOM) {
                        triggerSplitScreen(pkg, mode)
                    } else {
                        // Freeform launch doesn't need the toggle action
                        SplitScreenHelper.launchApp(this, pkg, mode)
                    }
                }
            }
            ACTION_ONE_HANDED -> {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    android.widget.Toast.makeText(this, "One-Handed Mode triggered", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_PREVIOUS_APP -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }, 200)
            }
            ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ACTION_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ACTION_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ACTION_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ACTION_LOCK_SCREEN -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            ACTION_TRIGGER_SHORTCUT -> {
                val shortcut = intent.getStringExtra("shortcut")
                if (shortcut == "smartedge.shortcut.one_hand") {
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.post {
                        android.widget.Toast.makeText(this, "One-Handed Mode triggered", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    // Attempting standard fallback if the OEM supports it via AccessibilityService
                    // true specific one-handed mode intents are heavily fragmented
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        // In Android 12+, there's no public GLOBAL_ACTION_ONE_HANDED.
                        // We rely on standard gesture dispatch or root if really necessary.
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Triggers split-screen for the given package and mode.
     *
     * Strategy 1 (AOSP): Use GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN to pin the foreground app,
     *   then launch the second app adjacent to it.
     * Strategy 2 (Origin OS / Vivo / OEMs that block the toggle): Skip the toggle and
     *   launch the second app directly with split-screen windowing mode flags. The OEM's
     *   own window manager handles placing it in split.
     */
    private fun triggerSplitScreen(pkg: String, mode: Int) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val isVivo = VivoUtils.isVivo()

        if (isVivo) {
            SplitScreenHelper.launchApp(this, pkg, mode)
        } else {
            // Standard AOSP path: toggle split, wait for animation, then launch second app
            val toggled = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

            // On many AOSP/Pixel versions, we need a significant delay for the system to dock the first app.
            // If toggle failed (e.g. only one app open), we still try to launch adjacent.
            val delay = if (toggled) 1000L else 500L
            handler.postDelayed({
                SplitScreenHelper.launchApp(this, pkg, mode)
            }, delay)
        }
    }

    /**
     * Authoritative foreground: the package of the active window in the
     * accessibility window list (needs flagRetrieveInteractiveWindows).
     * The launcher and its overlay pages (e.g. MagicOS hiboard) re-announce
     * themselves with window events DURING app-launch animations, so
     * "last event wins" tracking lets the foreground flip back to the
     * launcher while the user is already inside the app — showing the
     * onlyOnHome handle over that app (verified 2026-09-05 19:47:29,
     * exported log). The active window is the system's own notion of what
     * is focused; window events only trigger the re-check. Returns null
     * when the list is unusable (retrieval flag not active on this service
     * binding yet) — callers then fall back to the event package.
     */
    private fun authoritativeForeground(): String? {
        val list = windows
        if (list.isEmpty()) return null
        val active = list.firstOrNull { it.isActive } ?: return null
        // The window object itself exposes no package; the owning app is read
        // from the window's root node (needs canRetrieveWindowContent).
        return active.root?.packageName?.toString()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        checkImeVisibilityFromEvent(event)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.packageName?.toString()?.let { onLauncherContentEvent(it) }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventPkg = event.packageName?.toString() ?: return
            if (eventPkg == lastPackageName) return
            lastPackageName = eventPkg

            val className = event.className?.toString() ?: ""

            // Trust the active window over the event's package: launcher noise
            // during app-launch animations must not flip the tracked
            // foreground back to the launcher.
            val activePkg = authoritativeForeground()
            val packageName = activePkg ?: eventPkg

            val myPkg = this@PanelAccessibilityService.packageName
            val isSystemPkg = packageName == "android" || packageName == "com.android.systemui"
            val isImePkg = packageName in enabledImePackages()

            // Window classification for tracking + debug logs. Only real app
            // windows become "foreground": system overlays, IME windows (any
            // enabled IME — third-party keyboards don't use "InputMethod"
            // class names) and our own package must not pollute
            // currentForegroundPackage, or the onlyOnHome launcher check may
            // treat a stale/system package as "home" and re-show the handle.
            val filter = when {
                isImePkg || className.contains("InputMethod", ignoreCase = true) -> "ime"
                isSystemPkg -> "system"
                packageName == myPkg -> "own"
                else -> "app"
            }

            DebugLog.i(
                TAG, "win pkg=$eventPkg cls=${className.take(40)} filter=$filter" +
                    (if (activePkg != null && activePkg != eventPkg) " active=$activePkg" else "") +
                    (if (filter == "app" && panelPrefs.currentForegroundPackage != packageName)
                        " fgChange=${panelPrefs.currentForegroundPackage}->$packageName" else "")
            )

            if (filter == "app") {
                panelPrefs.currentForegroundPackage = packageName
            }

            if (filter == "app") {
                if (panelPrefs.serviceEnabled) {
                    val closeIntent = Intent(this, FloatingPanelService::class.java).apply {
                        action = FloatingPanelService.ACTION_CLOSE_PANEL
                    }
                    startService(closeIntent)

                    // Notify service to update game mode state based on new foreground package
                    val refreshIntent = Intent(this, FloatingPanelService::class.java).apply {
                        action = FloatingPanelService.ACTION_REFRESH
                    }
                    startService(refreshIntent)
                }
            }
        }
        
        // Check for immersive mode on window content changes too, as bounds might change
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            checkImmersiveMode()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        val stopIntent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_STOP
        }
        startService(stopIntent)
        return super.onUnbind(intent)
    }
}
