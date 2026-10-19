package com.aira.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Makes TV Home the screen you land on.
 *
 * Google TV doesn't let you pick a different home app, so when this is switched on
 * (Settings → System → Accessibility → TV Home) it watches for Google's own home
 * screen appearing — after power-on, waking from standby, or pressing Home — and
 * immediately opens TV Home in its place. It only looks at which app is on screen;
 * it never reads what's on the screen.
 */
class HomeOverrideService : AccessibilityService() {

    private var lastLaunch = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in STOCK_HOME_SCREENS) return

        // Avoid launching twice in a row if the stock screen fires several events.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return
        lastLaunch = now

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onInterrupt() {}

    companion object {
        /** Google TV and Android TV home screens. */
        private val STOCK_HOME_SCREENS = setOf(
            "com.google.android.apps.tv.launcherx",   // Google TV
            "com.google.android.tvlauncher",          // Android TV
            "com.google.android.leanbacklauncher"     // older Android TV
        )
    }
}
