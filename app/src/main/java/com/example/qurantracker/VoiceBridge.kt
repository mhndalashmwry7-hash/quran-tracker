package com.example.qurantracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

class VoiceBridge(private val act: Activity, private val web: WebView) {
    private var rec: SpeechRecognizer? = null
    private var on = false
    private var told = false
    private var errs = 0
    private val h = Handler(Looper.getMainLooper())

    private fun js(code: String) { web.post { web.evaluateJavascript(code, null) } }
    private fun status(s: String) { js("window.onVoiceStatus&&onVoiceStatus('" + s + "')") }
    private fun text(t: String?, fin: Boolean) {
        if (t.isNullOrEmpty()) return
        js("window.onVoiceText&&onVoiceText(" + JSONObject.quote(t) + "," + fin + ")")
    }
    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun makeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)
    }

    private fun listen() { if (on) rec?.startListening(makeIntent()) }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { if (!told) { told = true; status("listening") } }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onPartialResults(b: Bundle?) { errs = 0; text(first(b), false) }
        override fun onResults(b: Bundle?) { errs = 0; text(first(b), true); h.postDelayed({ listen() }, 30) }
        override fun onError(e: Int) {
            if (!on) return
            when (e) {
                9 -> { on = false; status("perm") }
                12, 13 -> { on = false; status("lang") }
                6, 7 -> h.postDelayed({ listen() }, 30)
                8 -> { rec?.cancel(); h.postDelayed({ listen() }, 400) }
                else -> {
                    errs++
                    if (errs > 8) { on = false; status("error") }
                    else h.postDelayed({ listen() }, 300)
                }
            }
        }
    }

    @JavascriptInterface
    fun start() {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77) }
            status("perm")
            return
        }
        act.runOnUiThread {
            if (!SpeechRecognizer.isRecognitionAvailable(act)) { status("error"); return@runOnUiThread }
            on = true; told = false; errs = 0
            rec?.destroy()
            rec = SpeechRecognizer.createSpeechRecognizer(act).also { it.setRecognitionListener(listener) }
            listen()
        }
    }

    @JavascriptInterface
    fun stop() {
        act.runOnUiThread {
            on = false
            h.removeCallbacksAndMessages(null)
            rec?.cancel()
            rec?.destroy()
            rec = null
            status("stopped")
        }
    }
}
