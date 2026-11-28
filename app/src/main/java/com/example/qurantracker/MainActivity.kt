package com.example.qurantracker

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

class MainActivity : Activity() {

    @Volatile private var topPx = 0
    private var darkIcons = false

    inner class Bridge {
        @JavascriptInterface
        fun topInset(): Float {
            var px = topPx
            if (px <= 0) {
                val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) px = resources.getDimensionPixelSize(id)
            }
            return px / resources.displayMetrics.density
        }

        @JavascriptInterface
        fun setDarkIcons(on: Boolean) {
            darkIcons = on
            runOnUiThread { applyBars() }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyBars() {
        var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (darkIcons) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.decorView.systemUiVisibility = f
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        applyBars()
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            topPx = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                insets.systemWindowInsetTop
            }
            v.onApplyWindowInsets(insets)
        }
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.addJavascriptInterface(VoiceBridge(this, web), "Voice")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }
}
