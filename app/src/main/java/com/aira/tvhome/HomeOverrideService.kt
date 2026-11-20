package com.aira.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.Calendar

/**
 * Makes TV Home the screen you land on, and runs the night-dimming features.
 *
 * Home override: Google TV doesn't let you pick a different home app, so when this
 * is switched on (Settings → System → Accessibility → TV Home) it watches for
 * Google's own home screen appearing — after power-on, waking from standby, or
 * pressing Home — and immediately opens TV Home in its place. It only looks at which
 * app is on screen; it never reads what's on the screen.
 *
 * Dimming: while connected it also hosts the screen-dim layer ([DimController]), a
 * once-a-minute check that applies the day/night schedule, and the phone control
 * server ([ControlServer]). All of it stops when the service is turned off.
 */
class HomeOverrideService : AccessibilityService() {

    private var lastLaunch = 0L

    private val main = Handler(Looper.getMainLooper())
    private var server: ControlServer? = null
    private var lastPhaseNight: Boolean? = null

    private val scheduleTick = object : Runnable {
        override fun run() {
            applyScheduleIfPhaseChanged()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Show whatever the schedule says for right now (e.g. dim if it's already night).
        reapplySchedule()
        main.removeCallbacks(scheduleTick)
        main.postDelayed(scheduleTick, 60_000)

        if (server == null) server = ControlServer(this)
        server?.start()
    }

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

    /** Set the dim layer to the level the schedule calls for at the current time. */
    fun reapplySchedule() {
        val night = isNightNow()
        lastPhaseNight = night
        val target = if (night) DimController.nightLevel(this) else DimController.dayLevel(this)
        DimController.setLevel(this, target)
    }

    /** Called every minute: only change the screen when day↔night actually flips. */
    private fun applyScheduleIfPhaseChanged() {
        val night = isNightNow()
        if (night != lastPhaseNight) {
            lastPhaseNight = night
            val target = if (night) DimController.nightLevel(this) else DimController.dayLevel(this)
            DimController.setLevel(this, target)
        }
    }

    private fun isNightNow(): Boolean {
        val start = DimController.parseMinutes(DimController.nightStart(this)) ?: return false
        val end = DimController.parseMinutes(DimController.nightEnd(this)) ?: return false
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return DimController.inWindow(nowMin, start, end)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(scheduleTick)
        server?.stop()
        DimController.remove()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        main.removeCallbacks(scheduleTick)
        server?.stop()
        DimController.remove()
        super.onDestroy()
    }

    companion object {
        /** Google TV and Android TV home screens. */
        private val STOCK_HOME_SCREENS = setOf(
            "com.google.android.apps.tv.launcherx",   // Google TV
            "com.google.android.tvlauncher",          // Android TV
            "com.google.android.leanbacklauncher"     // older Android TV
        )
    }
}
