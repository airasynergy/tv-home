package com.aira.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
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
            try { applyScheduleIfPhaseChanged(); checkSleepTimers() } catch (e: Exception) { }
            main.postDelayed(this, 60_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Start the control server and dimming independently of the home override,
        // and never let setup throw and take the whole service down.
        try {
            if (server == null) server = ControlServer(this)
            server?.start()
        } catch (e: Exception) { }
        // Apply the schedule off the critical path (the first tick fires in 3s).
        main.removeCallbacks(scheduleTick)
        main.postDelayed(scheduleTick, 3_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg !in STOCK_HOME_SCREENS) return
            // If our launcher is already on screen, do nothing — relaunching here is
            // exactly what caused the ping-pong loop that killed the app.
            if (MainActivity.active) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastLaunch < 1500) return
            lastLaunch = now

            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) { }
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

    fun openNomorflix() = openWeb("https://nomorflix.cc", "Nomorflix", "nomorflix", 0xFFE50914.toInt(), 0xFF5A0308.toInt())

    fun openWeb(url: String, name: String, wordmark: String = name.lowercase(),
                top: Int = 0xFF1C1C1E.toInt(), bottom: Int = 0xFF000000.toInt()) {
        startActivity(
            Intent(this, WebAppActivity::class.java)
                .putExtra(WebAppActivity.EXTRA_URL, url)
                .putExtra(WebAppActivity.EXTRA_NAME, name)
                .putExtra(WebAppActivity.EXTRA_WORDMARK, wordmark)
                .putExtra(WebAppActivity.EXTRA_COLOR_TOP, top)
                .putExtra(WebAppActivity.EXTRA_COLOR_BOTTOM, bottom)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ── Remote: D-pad routes into the open web app; the rest work system-wide ──
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /** Arrows / OK go to the on-screen web app (e.g. Nomorflix), where the remote struggles. */
    fun navKey(code: Int) { WebAppActivity.current?.remoteKey(code) }

    fun volUp() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    fun volDown() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    fun toggleMute() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)

    fun setVolumePct(pct: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct / 100).coerceIn(0, max), 0)
    }

    fun volumePct(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max == 0) return 0
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun isMuted(): Boolean = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    /** Play/pause, fast-forward, rewind — sent to whatever is playing, system-wide. */
    fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun launchApp(pkg: String) {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { }
    }

    /** Installed openable apps, as JSON, for the phone's Apps tab. */
    fun appsJson(): String {
        val pm = packageManager
        val seen = HashSet<String>()
        val items = ArrayList<Pair<String, String>>()  // label, package
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val label = ri.loadLabel(pm).toString()
                items.add(label to pkg)
            }
        }
        items.sortBy { it.first.lowercase() }
        val sb = StringBuilder("[")
        items.take(40).forEachIndexed { i, (label, pkg) ->
            if (i > 0) sb.append(",")
            sb.append("{\"n\":\"").append(jsonEscape(label)).append("\",\"p\":\"").append(pkg).append("\"}")
        }
        return sb.append("]").toString()
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\t", " ")

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
