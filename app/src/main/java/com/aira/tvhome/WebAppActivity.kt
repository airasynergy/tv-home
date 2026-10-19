package com.aira.tvhome

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Opens a website full-screen like an app.
 * Websites aren't built for TV remotes, so the D-pad moves an on-screen
 * pointer: arrows move it, OK clicks, pushing against the top/bottom edge
 * scrolls the page, Back goes back (or closes the app).
 */
class WebAppActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            // Block pop-up windows (common on streaming sites).
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // Stay inside the web view; ignore intent://, market:// etc.
                return scheme != "http" && scheme != "https"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                web.visibility = View.GONE
                cursor.visibility = View.GONE
            }

            override fun onHideCustomView() = exitVideoFullscreen()
        }

        cursor = CursorView(this)
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(cursor, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        hideSystemBars()

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "https://nomorflix.com")
        web.requestFocus()
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
        cursor.visibility = View.VISIBLE
        cursor.wake()
        hideSystemBars()
    }

    // ── Remote control → pointer ────────────────────────────────────────────
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // In full-screen video, let the player handle the remote normally.
        if (fullscreenView != null) return super.dispatchKeyEvent(e)

        val step = cursorStep(e.repeatCount)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (e.action == KeyEvent.ACTION_DOWN) move(0f, -step); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (e.action == KeyEvent.ACTION_DOWN) move(0f, step); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (e.action == KeyEvent.ACTION_DOWN) move(-step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (e.action == KeyEvent.ACTION_DOWN) move(step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) touch(MotionEvent.ACTION_DOWN)
                if (e.action == KeyEvent.ACTION_UP) touch(MotionEvent.ACTION_UP)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Starts slow for precision, speeds up while the button is held. */
    private fun cursorStep(repeat: Int): Float {
        val d = resources.displayMetrics.density
        return (10f + minOf(repeat, 24) * 2.2f) * d
    }

    private fun move(dx: Float, dy: Float) {
        cursor.wake()
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val nx = cursor.x0 + dx
        val ny = cursor.y0 + dy
        cursor.x0 = nx.coerceIn(0f, w - 1)
        cursor.y0 = ny.coerceIn(0f, h - 1)

        // Pushing past an edge scrolls the page instead.
        val density = resources.displayMetrics.density
        val sx = when { nx < 0 -> nx; nx > w - 1 -> nx - (w - 1); else -> 0f }
        val sy = when { ny < 0 -> ny; ny > h - 1 -> ny - (h - 1); else -> 0f }
        if (sx != 0f || sy != 0f) {
            val cssX = (sx * 3 / density).toInt()
            val cssY = (sy * 3 / density).toInt()
            web.evaluateJavascript(
                "(function(){var e=document.elementFromPoint(${(cursor.x0 / density).toInt()},${(cursor.y0 / density).toInt()});" +
                    "while(e&&e!==document.body){var s=getComputedStyle(e);" +
                    "if(/(auto|scroll)/.test(s.overflowY+s.overflowX)&&(e.scrollHeight>e.clientHeight||e.scrollWidth>e.clientWidth)){e.scrollBy($cssX,$cssY);return;}" +
                    "e=e.parentElement;}window.scrollBy($cssX,$cssY);})()",
                null
            )
        }
        cursor.invalidate()
    }

    private var downTime = 0L

    private fun touch(action: Int) {
        cursor.wake()
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, cursor.x0, cursor.y0, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(ev)
        ev.recycle()
        cursor.pressed = action == MotionEvent.ACTION_DOWN
        cursor.invalidate()
        if (action == MotionEvent.ACTION_UP) web.requestFocus()
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
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ── The pointer itself ──────────────────────────────────────────────────
    private class CursorView(context: Context) : View(context) {
        var x0 = -1f
        var y0 = -1f
        var pressed = false
        private val d = context.resources.displayMetrics.density
        private val fadeHandler = Handler(Looper.getMainLooper())
        private val fadeOut = Runnable { animate().alpha(0f).setDuration(400).start() }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

        init {
            isFocusable = false
            isClickable = false
        }

        /** Show the pointer; it fades away after a few idle seconds. */
        fun wake() {
            fadeHandler.removeCallbacks(fadeOut)
            animate().cancel()
            alpha = 1f
            fadeHandler.postDelayed(fadeOut, 3500)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (x0 < 0) { x0 = w / 2f; y0 = h / 2f; wake() }
        }

        override fun onDraw(canvas: Canvas) {
            val r = (if (pressed) 9f else 11f) * d
            canvas.drawCircle(x0, y0, r + 7f * d, halo)
            canvas.drawCircle(x0, y0, r, fill)
            canvas.drawCircle(x0, y0, r, ring)
        }
    }
}
