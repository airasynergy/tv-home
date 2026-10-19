package com.aira.tvhome

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.sign

/**
 * Opens a website full-screen like an app, tuned for a TV remote.
 *
 *  • Arrows glide a mouse pointer (smooth, speeds up while held).
 *  • The pointer is a real mouse to the site: hover menus open and close,
 *    and pushing against an edge scrolls whatever is under it with the
 *    mouse wheel (so long episode lists keep loading).
 *  • OK clicks. Back goes back. Menu reloads.
 *  • Play/Pause, Fast-forward and Rewind on the remote control the video.
 *  • The full desktop layout is requested, so pages show everything.
 */
class WebAppActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_WORDMARK = "wordmark"
        const val EXTRA_COLOR_TOP = "colorTop"
        const val EXTRA_COLOR_BOTTOM = "colorBottom"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var progress: View
    private lateinit var splash: View
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    // ── Setup ────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            builtInZoomControls = false
            offscreenPreRaster = true
            // Block pop-up windows and ad tabs.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // Ask for the full desktop site rather than the cut-down phone layout.
            userAgentString = desktopUserAgent()
        }
        // Embedded players and episode lists often need cookies from another site.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // ignore intent://, market://, etc.
            }

            override fun onPageFinished(view: WebView, url: String) = hideSplash()

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreate() // the page crashed: reopen cleanly instead of closing the app
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = showProgress(newProgress)

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
                root.addView(view, root.indexOfChild(cursor), FrameLayout.LayoutParams(MATCH, MATCH))
                web.visibility = View.INVISIBLE
            }

            override fun onHideCustomView() = exitVideoFullscreen()
        }

        progress = View(this).apply {
            setBackgroundColor(0xE6FFFFFF.toInt())
            pivotX = 0f
            scaleX = 0f
        }
        splash = buildSplash()
        cursor = CursorView(this)

        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(progress, FrameLayout.LayoutParams(MATCH, (3 * density).toInt(), Gravity.TOP))
        root.addView(cursor, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        hideSystemBars()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
            splash.visibility = View.GONE
        } else {
            web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "https://nomorflix.com")
            handler.postDelayed({ hideSplash() }, 6000) // never hide the site for long
        }
        web.requestFocus()
    }

    /** Desktop Chrome user agent that matches this TV's real browser engine version. */
    private fun desktopUserAgent(): String {
        val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(this))?.value
            ?: "Chrome/130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun buildSplash(): View {
        val top = intent.getIntExtra(EXTRA_COLOR_TOP, 0xFF1C1C1E.toInt())
        val bottom = intent.getIntExtra(EXTRA_COLOR_BOTTOM, Color.BLACK)
        return FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom, Color.BLACK)
            )
            addView(TextView(this@WebAppActivity).apply {
                text = intent.getStringExtra(EXTRA_WORDMARK) ?: intent.getStringExtra(EXTRA_NAME) ?: ""
                setTextColor(Color.WHITE)
                textSize = 46f
                typeface = resources.getFont(R.font.inter_display_bold)
                letterSpacing = -0.03f
                alpha = 0f
                animate().alpha(1f).setDuration(400).setInterpolator(EASE).start()
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE || splash.tag == "hiding") return
        splash.tag = "hiding"
        splash.animate().alpha(0f).setDuration(420).setInterpolator(EASE)
            .withEndAction { splash.visibility = View.GONE }.start()
    }

    private fun showProgress(p: Int) {
        progress.animate().cancel()
        if (p >= 100) {
            progress.animate().scaleX(1f).setDuration(150).withEndAction {
                progress.animate().alpha(0f).setDuration(300).start()
            }.start()
        } else {
            progress.alpha = 1f
            progress.animate().scaleX(p / 100f).setDuration(200).setInterpolator(EASE).start()
        }
        if (p >= 70) hideSplash()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun exitVideoFullscreen() {
        fullscreenView?.let { root.removeView(it) }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        web.visibility = View.VISIBLE
        cursor.wake()
        hideSystemBars()
    }

    /** Whatever the pointer is over: the page, or the full-screen video player. */
    private fun target(): View = fullscreenView ?: web

    // ── Remote control ───────────────────────────────────────────────────────
    private var dirX = 0
    private var dirY = 0
    private var holdStart = 0L
    private var lastFrameNs = 0L
    private var looping = false
    private var lastWheel = 0L
    private var hovering = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nowNs: Long) {
            if (dirX == 0 && dirY == 0) { looping = false; lastFrameNs = 0L; return }
            val dt = if (lastFrameNs == 0L) 0.016f else ((nowNs - lastFrameNs) / 1e9f).coerceAtMost(0.05f)
            lastFrameNs = nowNs
            val held = (SystemClock.uptimeMillis() - holdStart) / 1000f
            if (held > 0.16f) {
                // Glide: starts gentle for precision, eases up to full speed over ~0.9s.
                val ramp = ((held - 0.16f) / 0.9f).coerceIn(0f, 1f)
                val speedDp = 380f + 1250f * ramp * ramp
                val step = speedDp * density * dt
                moveBy(dirX * step, dirY * step)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { direction(0, -1, down, first); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { direction(0, 1, down, first); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { direction(-1, 0, down, first); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { direction(1, 0, down, first); return true }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (first) touch(MotionEvent.ACTION_DOWN)
                if (e.action == KeyEvent.ACTION_UP) touch(MotionEvent.ACTION_UP)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) video("v.paused ? v.play() : v.pause()")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) video("v.currentTime += 10"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) video("v.currentTime -= 10"); return true }

            KeyEvent.KEYCODE_MENU -> { if (first) web.reload(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun direction(x: Int, y: Int, down: Boolean, first: Boolean) {
        if (down) {
            if (!first) return // we animate per frame; ignore the remote's auto-repeat
            val wasIdle = dirX == 0 && dirY == 0
            if (x != 0) dirX = x
            if (y != 0) dirY = y
            if (wasIdle) holdStart = SystemClock.uptimeMillis()
            val wasHidden = cursor.alpha < 0.5f
            cursor.wake()
            if (!wasHidden) moveBy(x * 16f * density, y * 16f * density) // a tap = a small, precise nudge
            if (!looping) {
                looping = true
                Choreographer.getInstance().postFrameCallback(frame)
            }
        } else {
            if (x != 0 && dirX == x) dirX = 0
            if (y != 0 && dirY == y) dirY = 0
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        cursor.wake()
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val nx = cursor.x0 + dx
        val ny = cursor.y0 + dy
        val margin = 2f
        cursor.x0 = nx.coerceIn(margin, w - margin)
        cursor.y0 = ny.coerceIn(margin, h - margin)

        // Pushing past an edge scrolls whatever is under the pointer, like a mouse wheel.
        val overX = nx - cursor.x0
        val overY = ny - cursor.y0
        val now = SystemClock.uptimeMillis()
        if ((overX != 0f || overY != 0f) && now - lastWheel > 60) {
            lastWheel = now
            dispatchMouse(MotionEvent.ACTION_SCROLL, v = -sign(overY), h = sign(overX))
        }

        dispatchMouse(if (hovering) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_HOVER_ENTER)
        hovering = true
        cursor.invalidate()
    }

    /** Sends a genuine mouse event so hover menus, tooltips and wheel scrolling work. */
    private fun dispatchMouse(action: Int, v: Float = 0f, h: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursor.x0
            y = cursor.y0
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            setAxisValue(MotionEvent.AXIS_HSCROLL, h)
        })
        val ev = MotionEvent.obtain(
            now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        target().dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private var downTime = 0L

    private fun touch(action: Int) {
        cursor.wake()
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, cursor.x0, cursor.y0, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        target().dispatchTouchEvent(ev)
        ev.recycle()
        cursor.isDown = action == MotionEvent.ACTION_DOWN
        cursor.invalidate()
        if (action == MotionEvent.ACTION_UP) {
            target().requestFocus()
            // Re-establish hover so menus that opened on click stay tracked.
            dispatchMouse(MotionEvent.ACTION_HOVER_MOVE)
        }
    }

    /** Runs a snippet against the page's playing (or first) video. */
    private fun video(js: String) {
        web.evaluateJavascript(
            "(function(){var vs=[].slice.call(document.querySelectorAll('video'));" +
                "var v=vs.find(function(x){return !x.paused})||vs[0];if(v){$js}})()",
            null
        )
    }

    @Deprecated("Uses Back to navigate web history")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> exitVideoFullscreen()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    override fun onResume() { super.onResume(); web.onResume(); hideSystemBars() }
    override fun onPause() {
        dirX = 0; dirY = 0
        web.onPause()
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ── The pointer ──────────────────────────────────────────────────────────
    private class CursorView(context: Context) : View(context) {
        var x0 = -1f
        var y0 = -1f
        var isDown = false
        private val d = context.resources.displayMetrics.density
        private val fadeHandler = Handler(Looper.getMainLooper())
        private val fadeOut = Runnable { animate().alpha(0f).setDuration(500).start() }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x73000000; style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }

        init {
            isFocusable = false
            isClickable = false
            alpha = 0f
        }

        /** Show the pointer; it fades away after a few idle seconds. */
        fun wake() {
            fadeHandler.removeCallbacks(fadeOut)
            if (alpha < 1f) animate().alpha(1f).setDuration(120).start()
            fadeHandler.postDelayed(fadeOut, 4000)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (x0 < 0) { x0 = w / 2f; y0 = h / 2f; wake() }
        }

        override fun onDraw(canvas: Canvas) {
            val r = (if (isDown) 7.5f else 9f) * d
            canvas.drawCircle(x0, y0 + 1.5f * d, r + 1f * d, shadow)
            canvas.drawCircle(x0, y0, r + 6f * d, glow)
            canvas.drawCircle(x0, y0, r, fill)
            canvas.drawCircle(x0, y0, r, ring)
        }
    }
}
