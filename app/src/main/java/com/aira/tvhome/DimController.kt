package com.aira.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The on-screen layer behind night dimming, a warm tint, and the sleep blackout.
 *
 * Everything is drawn with one black-to-amber layer the accessibility service owns,
 * on top of every app (video included). It's done in software, so it works on TVs
 * whose backlight can't be set over adb. The layer never takes touch or focus, so it
 * only changes what you see.
 *
 *  - dim      0f..[MAX]  — how dark the screen goes
 *  - warmth   0f..1f     — how far the tint shifts toward warm amber (less blue light)
 *  - asleep   on/off     — "rest mode": a near-black blackout you wake from the phone
 *
 * dim and the day/night schedule live in SharedPreferences so the phone page and the
 * on-TV scheduler agree. `asleep` is runtime only — a restart wakes the TV.
 */
object DimController {

    /** Never dim past this in normal use — leave the picture visible. */
    const val MAX = 0.80f

    /** Rest mode goes almost fully black (above MAX on purpose). */
    private const val SLEEP_ALPHA = 0.96f

    /** Warm candlelight the tint blends toward. */
    private val AMBER = Color.rgb(255, 147, 41)

    private const val FILE = "dim"
    const val KEY_LEVEL = "level"
    const val KEY_WARMTH = "warmth"
    const val KEY_NIGHT_START = "nightStart"
    const val KEY_NIGHT_END = "nightEnd"
    const val KEY_NIGHT_LEVEL = "nightLevel"
    const val KEY_DAY_LEVEL = "dayLevel"
    const val KEY_OFF_ENABLED = "autoOffEnabled"
    const val KEY_OFF_TIME = "autoOffTime"
    const val KEY_ONESHOT_AT = "oneshotOffAt"

    /** Port the phone control page is served on. */
    const val PORT = 8099

    // Defaults: dim to 60% from 10pm, clear at 7am; no warmth; auto-off off.
    const val DEF_NIGHT_START = "22:00"
    const val DEF_NIGHT_END = "07:00"
    const val DEF_NIGHT_LEVEL = 0.60f
    const val DEF_DAY_LEVEL = 0.0f

    private var overlay: View? = null
    private var wm: WindowManager? = null
    private var asleep = false

    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nightStart(c: Context): String = prefs(c).getString(KEY_NIGHT_START, DEF_NIGHT_START) ?: DEF_NIGHT_START
    fun nightEnd(c: Context): String = prefs(c).getString(KEY_NIGHT_END, DEF_NIGHT_END) ?: DEF_NIGHT_END
    fun nightLevel(c: Context): Float = prefs(c).getFloat(KEY_NIGHT_LEVEL, DEF_NIGHT_LEVEL)
    fun dayLevel(c: Context): Float = prefs(c).getFloat(KEY_DAY_LEVEL, DEF_DAY_LEVEL)
    fun level(c: Context): Float = prefs(c).getFloat(KEY_LEVEL, DEF_DAY_LEVEL)
    fun warmth(c: Context): Float = prefs(c).getFloat(KEY_WARMTH, 0f)
    fun autoOffEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_OFF_ENABLED, false)
    fun autoOffTime(c: Context): String = prefs(c).getString(KEY_OFF_TIME, "23:30") ?: "23:30"
    fun oneshotAt(c: Context): Long = prefs(c).getLong(KEY_ONESHOT_AT, 0L)
    fun isAsleep(): Boolean = asleep

    fun saveSchedule(c: Context, nightStart: String, nightEnd: String, nightLevel: Float, dayLevel: Float) {
        prefs(c).edit()
            .putString(KEY_NIGHT_START, nightStart)
            .putString(KEY_NIGHT_END, nightEnd)
            .putFloat(KEY_NIGHT_LEVEL, nightLevel.coerceIn(0f, MAX))
            .putFloat(KEY_DAY_LEVEL, dayLevel.coerceIn(0f, MAX))
            .apply()
    }

    fun saveAutoOff(c: Context, enabled: Boolean, time: String) {
        prefs(c).edit().putBoolean(KEY_OFF_ENABLED, enabled).putString(KEY_OFF_TIME, time).apply()
    }

    /** Arm a one-off blackout at [atMillis] (0 clears it). Overrides the recurring timer for the day. */
    fun setOneshot(c: Context, atMillis: Long) {
        prefs(c).edit().putLong(KEY_ONESHOT_AT, atMillis).apply()
    }

    // ── Live controls ─────────────────────────────────────────────────────────
    fun setLevel(service: AccessibilityService, level: Float) {
        prefs(service).edit().putFloat(KEY_LEVEL, level.coerceIn(0f, MAX)).apply()
        render(service)
    }

    fun setWarmth(service: AccessibilityService, warmth: Float) {
        prefs(service).edit().putFloat(KEY_WARMTH, warmth.coerceIn(0f, 1f)).apply()
        render(service)
    }

    fun sleep(service: AccessibilityService) { asleep = true; render(service) }
    fun wake(service: AccessibilityService) { asleep = false; render(service) }

    /** Draw the layer from the current dim, warmth and sleep state. */
    fun render(service: AccessibilityService) {
        val color: Int
        val alpha: Float
        if (asleep) {
            color = Color.BLACK
            alpha = SLEEP_ALPHA
        } else {
            val dim = level(service).coerceIn(0f, MAX)
            val warm = warmth(service).coerceIn(0f, 1f)
            // Warmth alone still tints a little, like Night Light.
            alpha = maxOf(dim, warm * 0.5f).coerceAtMost(MAX)
            color = blend(Color.BLACK, AMBER, warm)
        }

        if (alpha <= 0.001f) { remove(); return }
        val mgr = wm
            ?: (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }

        var v = overlay
        if (v == null) {
            v = View(service)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                mgr.addView(v, lp)
                overlay = v
            } catch (e: Exception) {
                overlay = null
                return
            }
        }
        v.setBackgroundColor(color)
        v.alpha = alpha
    }

    fun remove() {
        val v = overlay ?: return
        try { wm?.removeView(v) } catch (e: Exception) { /* already gone */ }
        overlay = null
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.rgb(r, g, b)
    }

    // ── Schedule maths ────────────────────────────────────────────────────────
    fun parseMinutes(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun inWindow(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        return if (startMin <= endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
    }
}
