package com.aira.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.Calendar

/**
 * Makes TV Home the screen you land on, and runs the dimming / sleep features.
 *
 * Home override: Google TV doesn't let you pick a different home app, so when this
 * is switched on (Settings → System → Accessibility → TV Home) it watches for
 * Google's own home screen appearing and immediately opens TV Home in its place. It
 * only looks at which app is on screen; it never reads screen content.
 *
 * While connected it also hosts the dim/warmth/sleep layer ([DimController]), a
 * once-a-minute check that applies the day/night schedule and the sleep timers, and
 * the phone control server ([ControlServer]). All of it stops when switched off.
 */
class HomeOverrideService : AccessibilityService() {

    private var lastLaunch = 0L

    private val main = Handler(Looper.getMainLooper())
    private var server: ControlServer? = null
    private var lastPhaseNight: Boolean? = null
    private var lastAutoOffStamp: String? = null

    private val scheduleTick = object : Runnable {
        override fun run() {
            applyScheduleIfPhaseChanged()
            checkSleepTimers()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return
        lastLaunch = now

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    // ── Called from the phone control server (always on the main thread) ───────
    fun reapplySchedule() {
        val night = isNightNow()
        lastPhaseNight = night
        DimController.setLevel(this, if (night) DimController.nightLevel(this) else DimController.dayLevel(this))
    }

    fun sleepTv() = DimController.sleep(this)
    fun wakeTv() = DimController.wake(this)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    fun openNomorflix() {
        startActivity(
            Intent(this, WebAppActivity::class.java)
                .putExtra(WebAppActivity.EXTRA_URL, "https://nomorflix.cc")
                .putExtra(WebAppActivity.EXTRA_NAME, "Nomorflix")
                .putExtra(WebAppActivity.EXTRA_WORDMARK, "nomorflix")
                .putExtra(WebAppActivity.EXTRA_COLOR_TOP, 0xFFE50914.toInt())
                .putExtra(WebAppActivity.EXTRA_COLOR_BOTTOM, 0xFF5A0308.toInt())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ── Per-minute schedule + timers ──────────────────────────────────────────
    private fun applyScheduleIfPhaseChanged() {
        val night = isNightNow()
        if (night != lastPhaseNight) {
            lastPhaseNight = night
            DimController.setLevel(this, if (night) DimController.nightLevel(this) else DimController.dayLevel(this))
        }
    }

    private fun checkSleepTimers() {
        // One-off timer the user set for today (overrides the recurring one).
        val oneshot = DimController.oneshotAt(this)
        if (oneshot in 1..System.currentTimeMillis()) {
            DimController.setOneshot(this, 0L)
            DimController.sleep(this)
            return
        }
        // Recurring auto-off, fired once per occurrence.
        if (DimController.autoOffEnabled(this) && !DimController.isAsleep()) {
            val target = DimController.parseMinutes(DimController.autoOffTime(this)) ?: return
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val stamp = "${cal.get(Calendar.DAY_OF_YEAR)}:$target"
            if (nowMin == target && stamp != lastAutoOffStamp) {
                lastAutoOffStamp = stamp
                DimController.sleep(this)
            }
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
        private val STOCK_HOME_SCREENS = setOf(
            "com.google.android.apps.tv.launcherx",   // Google TV
            "com.google.android.tvlauncher",          // Android TV
            "com.google.android.leanbacklauncher"     // older Android TV
        )
    }
}
