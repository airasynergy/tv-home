package com.aira.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Night dimming for the whole TV.
 *
 * Lays a black layer over everything on screen — any app, video included — and
 * fades it in to soften the picture at night. It's done purely in software, as a
 * window the accessibility service owns, so it works on TVs whose backlight can't
 * be changed over adb. The layer never takes touch or focus, so it changes nothing
 * about how the TV is used; it only dims what you see.
 *
 * Levels are a fraction from 0f (off, fully clear) to [MAX]. Settings — the current
 * level and the day/night schedule — live in one SharedPreferences file so the
 * phone control page and the on-TV scheduler always agree.
 */
object DimController {

    /** Never go fully black — leave the picture visible. */
    const val MAX = 0.80f

    private const val FILE = "dim"
    const val KEY_LEVEL = "level"
    const val KEY_NIGHT_START = "nightStart"
    const val KEY_NIGHT_END = "nightEnd"
    const val KEY_NIGHT_LEVEL = "nightLevel"
    const val KEY_DAY_LEVEL = "dayLevel"

    /** Port the phone control page is served on. */
    const val PORT = 8099

    // Defaults: dim to 60% from 10pm, clear at 7am.
    const val DEF_NIGHT_START = "22:00"
    const val DEF_NIGHT_END = "07:00"
    const val DEF_NIGHT_LEVEL = 0.60f
    const val DEF_DAY_LEVEL = 0.0f

    private var overlay: View? = null
    private var wm: WindowManager? = null

    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nightStart(c: Context): String = prefs(c).getString(KEY_NIGHT_START, DEF_NIGHT_START) ?: DEF_NIGHT_START
    fun nightEnd(c: Context): String = prefs(c).getString(KEY_NIGHT_END, DEF_NIGHT_END) ?: DEF_NIGHT_END
    fun nightLevel(c: Context): Float = prefs(c).getFloat(KEY_NIGHT_LEVEL, DEF_NIGHT_LEVEL)
    fun dayLevel(c: Context): Float = prefs(c).getFloat(KEY_DAY_LEVEL, DEF_DAY_LEVEL)
    fun level(c: Context): Float = prefs(c).getFloat(KEY_LEVEL, DEF_DAY_LEVEL)

    /** Save the schedule from the phone page. */
    fun saveSchedule(c: Context, nightStart: String, nightEnd: String, nightLevel: Float, dayLevel: Float) {
        prefs(c).edit()
            .putString(KEY_NIGHT_START, nightStart)
            .putString(KEY_NIGHT_END, nightEnd)
            .putFloat(KEY_NIGHT_LEVEL, nightLevel.coerceIn(0f, MAX))
            .putFloat(KEY_DAY_LEVEL, dayLevel.coerceIn(0f, MAX))
            .apply()
    }

    /** Remember the level so it survives a restart, then show it. */
    fun setLevel(service: AccessibilityService, levelRaw: Float) {
        val level = levelRaw.coerceIn(0f, MAX)
        prefs(service).edit().putFloat(KEY_LEVEL, level).apply()
        apply(service, level)
    }

    /** Put the layer on screen at [levelRaw] without changing the saved value. */
    fun apply(service: AccessibilityService, levelRaw: Float) {
        val level = levelRaw.coerceIn(0f, MAX)
        if (level <= 0.001f) { remove(); return }
        val mgr = wm
            ?: (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }

        var v = overlay
        if (v == null) {
            v = View(service).apply { setBackgroundColor(Color.BLACK) }
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
        v.alpha = level
    }

    /** Take the layer away. */
    fun remove() {
        val v = overlay ?: return
        try { wm?.removeView(v) } catch (e: Exception) { /* already gone */ }
        overlay = null
    }

    /** "HH:mm" → minutes since midnight, or null if it can't be read. */
    fun parseMinutes(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** Is [nowMin] inside the window [startMin, endMin), handling a wrap past midnight? */
    fun inWindow(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        return if (startMin <= endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
    }
}
