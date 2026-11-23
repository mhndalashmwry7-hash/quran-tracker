package com.example.qurantracker

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebView

class MainActivity : Activity() {

    inner class Bridge {
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun setFullscreen(on: Boolean) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 30) {
                    val c = window.insetsController
                    if (c != null) {
                        val bars = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                        if (on) {
                            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            c.hide(bars)
                        } else {
                            c.show(bars)
                        }
                    }
                } else {
                    window.decorView.systemUiVisibility = if (on) {
                        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    } else {
                        View.SYSTEM_UI_FLAG_VISIBLE
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }
}
