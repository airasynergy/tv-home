package com.aira.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Date

/**
 * Apple TV–style home screen for Google TV.
 *
 *  ┌ Good evening, Jaiden                          7:42 ┐
 *  │ Monday, October 19                                 │
 *  │                                                    │  ← Top Shelf: blurred art of
 *  │  WEB APP                                           │    the focused app fills the
 *  │  Nomorflix                                         │    background
 *  │  [■][■][■][■][■]   ← Top Row (your favourites)     │
 *  │  All Apps                                          │
 *  └  [■][■][■][■][■]   ← every other installed app     ┘
 *
 * Hold OK on any tile for options (add to / remove from Top Row, reorder, App Info).
 */
class MainActivity : Activity() {

    companion object {
        /** Name used in the greeting. */
        const val USER_NAME = "Jaiden"

        private const val COLUMNS = 5
        private const val SIDE_PADDING_DP = 72f
        private const val PREFS = "home"
        private const val KEY_FAVS = "topRow"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Apple's signature "ease out" curve. */
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

        /** Popular TV apps placed in the Top Row the first time, if installed. */
        private val DEFAULT_TOP_ROW = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
            "com.google.android.apps.youtube.music",
            "com.hbo.hbonow",
            "com.wbd.stream"
        )
    }

    // ── Website "apps". Add more lines here to pin more sites. ────────────────
    private val webApps = listOf(
        WebApp(
            name = "Nomorflix",
            url = "https://nomorflix.com",
            wordmark = "nomorflix",
            colorTop = 0xFFE50914.toInt(),
            colorBottom = 0xFF5A0308.toInt()
        )
        // WebApp("Aira Freight", "https://airafreight.com", "aira", 0xFF1E3A8A.toInt(), 0xFF0B1530.toInt()),
    )

    data class WebApp(
        val name: String,
        val url: String,
        val wordmark: String,
        val colorTop: Int,
        val colorBottom: Int
    )

    private class Tile(
        val key: String,
        val kind: String,
        val label: String,
        val subtitle: String,
        val banner: Drawable?,
        val icon: Drawable?,
        val wordmark: String?,
        val bgTop: Int,
        val bgBottom: Int,
        val accent: Int,
        val launch: () -> Unit
    )

    private lateinit var root: FrameLayout
    private lateinit var scroller: ScrollView
    private lateinit var dockRow: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var allAppsLabel: TextView
    private lateinit var hero: LinearLayout
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var greeting: TextView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var wallFront: ImageView
    private lateinit var wallBack: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var tiles: List<Tile> = emptyList()
    private var lastFocusedKey: String? = null
    private var currentWallKey: String? = null
    private var loadGeneration = 0
    private var menuOverlay: View? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }
    private val interMedium: Typeface by lazy { resources.getFont(R.font.inter_medium) }
    private val interSemibold: Typeface by lazy { resources.getFont(R.font.inter_semibold) }
    private val interDisplayBold: Typeface by lazy { resources.getFont(R.font.inter_display_bold) }
    private fun dp(v: Float) = v * density
    private fun dpi(v: Float) = (v * density).toInt()

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 10_000)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        scroller = findViewById(R.id.scroller)
        dockRow = findViewById(R.id.dockRow)
        grid = findViewById(R.id.grid)
        allAppsLabel = findViewById(R.id.allAppsLabel)
        hero = findViewById(R.id.hero)
        heroKicker = findViewById(R.id.heroKicker)
        heroTitle = findViewById(R.id.heroTitle)
        heroSub = findViewById(R.id.heroSub)
        greeting = findViewById(R.id.greeting)
        clock = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        wallFront = findViewById(R.id.wallA)
        wallBack = findViewById(R.id.wallB)

        if (Build.VERSION.SDK_INT >= 31) {
            val blur = RenderEffect.createBlurEffect(dp(44f), dp(44f), Shader.TileMode.CLAMP)
            wallFront.setRenderEffect(blur)
            wallBack.setRenderEffect(blur)
        }
        listOf(wallFront, wallBack).forEach { it.scaleX = 1.18f; it.scaleY = 1.18f }

        // Darken for legibility: lighter in the middle where the art shows, deep at the bottom.
        findViewById<View>(R.id.wallShade).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x99000000.toInt(), 0x4D000000, 0x8C000000.toInt(), 0xE6000000.toInt(), 0xFF000000.toInt())
        )
        // Soft vignette at the sides keeps the eye in the centre.
        findViewById<View>(R.id.wallVignette).background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x73000000, 0x00000000, 0x00000000, 0x59000000)
        )

        // The Top Shelf fills the upper part of the screen, so the Top Row sits low like Apple TV.
        val screenH = resources.displayMetrics.heightPixels
        hero.layoutParams = hero.layoutParams.apply { height = (screenH * 0.40f).toInt() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        loadTiles()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    @Deprecated("Home screen: Back returns to the top instead of closing")
    override fun onBackPressed() {
        if (menuOverlay != null) { closeMenu(); return }
        // Like Apple TV: Back from the grid jumps up to the Top Row.
        if (scroller.scrollY > 0) {
            scroller.smoothScrollTo(0, 0)
            dockRow.getChildAt(0)?.let { cardOf(it).requestFocus() }
        }
    }

    // ── Greeting + clock ─────────────────────────────────────────────────────
    private fun updateClock() {
        val now = Date()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val salutation = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Goodnight"
        }
        greeting.text = "$salutation, $USER_NAME"
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clock.text = DateFormat.format(pattern, now)
        dateView.text = DateFormat.format("EEEE, MMMM d", now)
    }

    // ── Loading every installed app (background thread) ──────────────────────
    private fun loadTiles() {
        val gen = ++loadGeneration
        Thread {
            val list = buildTiles()
            runOnUiThread {
                if (gen == loadGeneration && !isFinishing) {
                    tiles = list
                    render()
                }
            }
        }.start()
    }

    private fun buildTiles(): List<Tile> {
        val out = mutableListOf<Tile>()

        webApps.forEach { w ->
            out += Tile(
                key = "web:${w.url}",
                kind = "Web App",
                label = w.name,
                subtitle = w.url.removePrefix("https://").removePrefix("http://"),
                banner = null, icon = null, wordmark = w.wordmark,
                bgTop = w.colorTop, bgBottom = w.colorBottom, accent = w.colorTop,
                launch = {
                    startActivity(
                        Intent(this, WebAppActivity::class.java)
                            .putExtra(WebAppActivity.EXTRA_URL, w.url)
                            .putExtra(WebAppActivity.EXTRA_NAME, w.name)
                            .putExtra(WebAppActivity.EXTRA_WORDMARK, w.wordmark)
                            .putExtra(WebAppActivity.EXTRA_COLOR_TOP, w.colorTop)
                            .putExtra(WebAppActivity.EXTRA_COLOR_BOTTOM, w.colorBottom)
                    )
                }
            )
        }

        // Every app that can be opened: TV apps first (LEANBACK), then phone-style apps.
        val pm = packageManager
        val seen = HashSet<String>()
        val installed = mutableListOf<Tile>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                val pkg = ai.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                val banner = try { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) } catch (e: Exception) { null }
                val icon = try { ai.loadIcon(pm) } catch (e: Exception) { null }
                val accent = dominantColor(banner ?: icon)
                installed += Tile(
                    key = "pkg:$pkg",
                    kind = "App",
                    label = ri.loadLabel(pm).toString(),
                    subtitle = "",
                    banner = banner, icon = icon, wordmark = null,
                    // Icon-only apps get a rich backdrop tinted from their own colours.
                    bgTop = mix(accent, Color.BLACK, 0.45f),
                    bgBottom = mix(accent, Color.BLACK, 0.78f),
                    accent = accent,
                    launch = {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        safeStart(launchIntent)
                    }
                )
            }
        }
        out += installed.sortedBy { it.label.lowercase() }

        out += Tile(
            key = "settings",
            kind = "System",
            label = "Settings",
            subtitle = "",
            banner = null,
            icon = getDrawable(R.drawable.ic_settings),
            wordmark = null,
            bgTop = 0xFF636366.toInt(), bgBottom = 0xFF3A3A3C.toInt(),
            accent = 0xFF8E8E93.toInt(),
            launch = { safeStart(Intent(Settings.ACTION_SETTINGS)) }
        )
        return out
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open that app", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Can't open that app", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Top Row (favourites), saved on the TV ────────────────────────────────
    private fun topRowKeys(): MutableList<String> {
        val present = tiles.map { it.key }.toSet()
        val saved = prefs.getString(KEY_FAVS, null)
        if (saved != null) {
            return saved.split('\n').filter { it in present }.toMutableList()
        }
        // First launch: websites + popular streaming apps that are installed.
        val seed = mutableListOf<String>()
        webApps.forEach { seed += "web:${it.url}" }
        DEFAULT_TOP_ROW.forEach { if ("pkg:$it" in present && seed.size < 6) seed += "pkg:$it" }
        tiles.filter { it.key.startsWith("pkg:") }.forEach {
            if (seed.size < COLUMNS && it.key !in seed) seed += it.key
        }
        saveTopRow(seed)
        return seed
    }

    private fun saveTopRow(keys: List<String>) {
        prefs.edit().putString(KEY_FAVS, keys.joinToString("\n")).apply()
    }

    // ── Rendering ────────────────────────────────────────────────────────────
    private fun render() {
        val topKeys = topRowKeys()
        val byKey = tiles.associateBy { it.key }
        val top = topKeys.mapNotNull { byKey[it] }
        val rest = tiles.filter { it.key !in topKeys }

        val (w, h) = tileSize()
        var toFocus: View? = null

        dockRow.removeAllViews()
        top.forEachIndexed { i, t ->
            val cell = makeCell(t, w, h, inTopRow = true)
            (cell.layoutParams as LinearLayout.LayoutParams).marginEnd =
                if (i == top.lastIndex) 0 else gap()
            dockRow.addView(cell)
            if (t.key == lastFocusedKey) toFocus = cardOf(cell)
        }

        grid.removeAllViews()
        allAppsLabel.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        rest.chunked(COLUMNS).forEach { chunk ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            chunk.forEachIndexed { i, t ->
                val cell = makeCell(t, w, h, inTopRow = false)
                (cell.layoutParams as LinearLayout.LayoutParams).marginEnd =
                    if (i == chunk.lastIndex) 0 else gap()
                rowView.addView(cell)
                if (t.key == lastFocusedKey) toFocus = cardOf(cell)
            }
            grid.addView(rowView)
        }

        val target = toFocus
            ?: dockRow.getChildAt(0)?.let { cardOf(it) }
            ?: (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.let { cardOf(it) }
        target?.post { target.requestFocus() }
    }

    private fun gap() = dpi(36f)

    /** Five 16:9 tiles across the safe area, like Apple TV's home grid. */
    private fun tileSize(): Pair<Int, Int> {
        val usable = resources.displayMetrics.widthPixels - dpi(SIDE_PADDING_DP) * 2
        val w = (usable - gap() * (COLUMNS - 1)) / COLUMNS
        return w to (w * 9 / 16)
    }

    private fun cardOf(cell: View): View = (cell as ViewGroup).getChildAt(0)

    /** A tile plus the name that fades in underneath it when focused. */
    private fun makeCell(t: Tile, w: Int, h: Int, inTopRow: Boolean): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(w, WRAP)
        }

        val radius = dp(14f)
        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, h)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(t.bgTop, t.bgBottom)
            ).apply { cornerRadius = radius }
            // Hairline edge, like glass catching light.
            foreground = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.TRANSPARENT)
                setStroke(dpi(1f).coerceAtLeast(1), 0x1FFFFFFF)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(2f)
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = Color.BLACK
                outlineAmbientShadowColor = Color.BLACK
            }
        }

        when {
            t.banner != null -> card.addView(ImageView(this).apply {
                setImageDrawable(t.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH, MATCH))

            t.wordmark != null -> card.addView(TextView(this).apply {
                text = t.wordmark
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = interDisplayBold
                letterSpacing = -0.03f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH))

            t.icon != null -> card.addView(ImageView(this).apply {
                setImageDrawable(t.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                elevation = dp(2f)
            }, FrameLayout.LayoutParams((h * 0.5f).toInt(), (h * 0.5f).toInt(), Gravity.CENTER))
        }

        // Glass highlight across the top half.
        card.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x1FFFFFFF, 0x00FFFFFF)
            )
        }, FrameLayout.LayoutParams(MATCH, h / 2, Gravity.TOP))

        // Light sweep that glides across a tile when it's selected.
        val sheen = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF)
            )
            rotation = 18f
            alpha = 0f
        }
        card.addView(sheen, FrameLayout.LayoutParams((w * 0.45f).toInt(), (h * 1.6f).toInt(), Gravity.CENTER_VERTICAL))

        val label = TextView(this).apply {
            text = t.label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = interMedium
            letterSpacing = -0.005f
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0f
            setShadowLayer(10f, 0f, 1f, 0x99000000.toInt())
        }

        cell.addView(card)
        cell.addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dpi(16f)
            bottomMargin = dpi(if (inTopRow) 6f else 28f)
        })

        card.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.12f else 1f)
                .scaleY(if (hasFocus) 1.12f else 1f)
                .translationZ(if (hasFocus) dp(26f) else 0f)
                .setDuration(if (hasFocus) 280 else 220)
                .setInterpolator(EASE)
                .start()
            label.animate()
                .alpha(if (hasFocus) 1f else 0f)
                .translationY(if (hasFocus) dp(5f) else 0f)
                .setDuration(240)
                .setInterpolator(EASE)
                .start()
            if (hasFocus) {
                lastFocusedKey = t.key
                playSheen(sheen, w)
                onTileFocused(t, cell, inTopRow)
            }
        }
        card.setOnClickListener {
            // Small press-in, then open.
            it.animate().scaleX(1.05f).scaleY(1.05f).setDuration(90).setInterpolator(EASE).withEndAction {
                it.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).setInterpolator(EASE).start()
                t.launch()
            }.start()
        }
        card.setOnLongClickListener { showMenu(t, card); true }
        return cell
    }

    private fun playSheen(sheen: View, w: Int) {
        sheen.animate().cancel()
        sheen.translationX = -w * 0.7f
        sheen.alpha = 1f
        sheen.animate()
            .translationX(w * 1.2f)
            .setStartDelay(140)
            .setDuration(850)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction { sheen.alpha = 0f }
            .start()
    }

    private fun onTileFocused(t: Tile, cell: View, inTopRow: Boolean) {
        heroKicker.text = t.kind
        heroTitle.text = t.label
        heroSub.text = t.subtitle
        heroSub.visibility = if (t.subtitle.isEmpty()) View.GONE else View.VISIBLE
        listOf(heroKicker, heroTitle, heroSub).forEachIndexed { i, v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(8f)
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 40L).setDuration(320).setInterpolator(EASE).start()
        }

        setWallpaper(t)

        if (inTopRow) {
            // Top Row focused → show the full Top Shelf.
            scroller.smoothScrollTo(0, 0)
        } else {
            // Grid focused → slide the shelf away and keep the row comfortably on screen.
            val rowView = cell.parent as View
            val y = grid.top + rowView.top - dpi(120f)
            scroller.post { scroller.smoothScrollTo(0, y.coerceAtLeast(0)) }
        }
    }

    // ── Blurred wallpaper crossfade ──────────────────────────────────────────
    private fun setWallpaper(t: Tile) {
        if (t.key == currentWallKey) return
        currentWallKey = t.key

        val art: Drawable = when {
            t.wordmark != null ->
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.bgTop, t.bgBottom, Color.BLACK))
            Build.VERSION.SDK_INT >= 31 ->
                // A separate copy, so the tile and the wallpaper don't share one drawable.
                (t.banner ?: t.icon)?.constantState?.newDrawable(resources)?.mutate()
                    ?: GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.bgTop, t.bgBottom))
            else ->
                // Older Android has no blur, so use a soft colour wash instead.
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.accent, Color.BLACK))
        }

        val incoming = wallBack
        val outgoing = wallFront
        incoming.setImageDrawable(art)
        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(600).setInterpolator(EASE).start()
        outgoing.animate().alpha(0f).setDuration(600).setInterpolator(EASE).start()
        wallFront = incoming
        wallBack = outgoing
    }

    // ── Hold OK: Apple-style options menu ────────────────────────────────────
    private fun showMenu(t: Tile, anchor: View) {
        if (menuOverlay != null) return
        val keys = topRowKeys()
        val idx = keys.indexOf(t.key)
        val actions = mutableListOf<Pair<String, () -> Unit>>()

        if (idx >= 0) {
            if (idx > 0) actions += "Move Left" to { keys.add(idx - 1, keys.removeAt(idx)); commit(keys) }
            if (idx < keys.lastIndex) actions += "Move Right" to { keys.add(idx + 1, keys.removeAt(idx)); commit(keys) }
            actions += "Remove from Top Row" to { keys.removeAt(idx); commit(keys) }
        } else {
            actions += "Add to Top Row" to { keys += t.key; commit(keys) }
        }
        if (t.key.startsWith("pkg:")) {
            val pkg = t.key.removePrefix("pkg:")
            actions += "App Info" to {
                safeStart(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14f), dpi(22f), dpi(14f), dpi(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(26f)
                setColor(0xF21C1C1E.toInt())
                setStroke(dpi(1f).coerceAtLeast(1), 0x1AFFFFFF)
            }
            elevation = dp(30f)
            scaleX = 0.94f
            scaleY = 0.94f
        }
        panel.addView(TextView(this).apply {
            text = t.label
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = interSemibold
            letterSpacing = -0.015f
            setPadding(dpi(14f), 0, dpi(14f), dpi(14f))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val items = actions.map { (title, action) ->
            TextView(this).apply {
                text = title
                textSize = 17f
                typeface = interMedium
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(18f), 0, dpi(18f), 0)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                id = View.generateViewId()
                val idle = GradientDrawable().apply { cornerRadius = dp(14f); setColor(Color.TRANSPARENT) }
                val active = GradientDrawable().apply { cornerRadius = dp(14f); setColor(Color.WHITE) }
                background = idle
                setTextColor(0xE6FFFFFF.toInt())
                setOnFocusChangeListener { v, has ->
                    v.background = if (has) active else idle
                    setTextColor(if (has) Color.BLACK else 0xE6FFFFFF.toInt())
                    v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f)
                        .setDuration(160).setInterpolator(EASE).start()
                }
                setOnClickListener { closeMenu(); action() }
            }
        }
        items.forEach { panel.addView(it, LinearLayout.LayoutParams(MATCH, dpi(52f)).apply { topMargin = dpi(4f) }) }
        // Keep the remote inside the menu while it's open.
        items.forEachIndexed { i, v ->
            v.nextFocusLeftId = v.id
            v.nextFocusRightId = v.id
            v.nextFocusUpId = items[maxOf(0, i - 1)].id
            v.nextFocusDownId = items[minOf(items.lastIndex, i + 1)].id
        }

        overlay.addView(panel, FrameLayout.LayoutParams(dpi(380f), WRAP, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay.tag = anchor
        menuOverlay = overlay

        overlay.animate().alpha(1f).setDuration(200).setInterpolator(EASE).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(EASE).start()
        items.firstOrNull()?.requestFocus()
    }

    private fun closeMenu() {
        val overlay = menuOverlay ?: return
        menuOverlay = null
        (overlay.tag as? View)?.requestFocus()
        overlay.animate().alpha(0f).setDuration(160).setInterpolator(EASE)
            .withEndAction { root.removeView(overlay) }.start()
    }

    private fun commit(keys: List<String>) {
        saveTopRow(keys)
        render()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
    )

    /** Averages a tiny render of the app art to get its brand colour. */
    private fun dominantColor(d: Drawable?): Int {
        if (d == null) return 0xFF3A3A3C.toInt()
        return try {
            val size = 16
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val copy = d.constantState?.newDrawable()?.mutate() ?: return 0xFF3A3A3C.toInt()
            copy.setBounds(0, 0, size, size)
            copy.draw(Canvas(bmp))
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (x in 0 until size) for (y in 0 until size) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                val max = maxOf(Color.red(p), Color.green(p), Color.blue(p))
                val min = minOf(Color.red(p), Color.green(p), Color.blue(p))
                val wgt = 1 + (max - min) / 16 // favour colourful pixels over grey/white
                r += Color.red(p) * wgt; g += Color.green(p) * wgt; b += Color.blue(p) * wgt; n += wgt
            }
            bmp.recycle()
            if (n == 0L) 0xFF3A3A3C.toInt()
            else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } catch (e: Exception) {
            0xFF3A3A3C.toInt()
        }
    }
}
