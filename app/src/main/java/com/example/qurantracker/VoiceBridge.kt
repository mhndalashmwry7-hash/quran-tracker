package com.example.qurantracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class VoiceBridge(private val act: Activity, private val web: WebView) {
    private var model: Model? = null
    private var service: SpeechService? = null
    private var busy = false
    private val dir = File(act.filesDir, "vosk-model")
    private val url = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"

    private fun js(code: String) { web.post { web.evaluateJavascript(code, null) } }
    private fun status(s: String) { js("window.onVoiceStatus&&onVoiceStatus('" + s + "')") }

    private fun send(h: String?, fin: Boolean) {
        if (h == null) return
        val o = JSONObject(h)
        val t = if (fin) o.optString("text") else o.optString("partial")
        if (t.isNotEmpty()) js("window.onVoiceText&&onVoiceText(" + JSONObject.quote(t) + "," + fin + ")")
    }

    private fun download() {
        dir.deleteRecursively()
        dir.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        ZipInputStream(c.inputStream.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = e.name.substringAfter('/', "")
                if (name.isNotEmpty()) {
                    val f = File(dir, name)
                    if (!f.canonicalPath.startsWith(dir.canonicalPath)) throw SecurityException("zip")
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { z.copyTo(it) }
                    }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
        File(dir, ".done").writeText("ok")
    }

    @JavascriptInterface
    fun start() {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77) }
            status("perm")
            return
        }
        if (busy) return
        busy = true
        Thread {
            try {
                if (!File(dir, ".done").exists()) { status("download"); download() }
                val m = model ?: run { status("loading"); Model(dir.absolutePath).also { model = it } }
                val sv = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
                service = sv
                sv.startListening(object : RecognitionListener {
                    override fun onPartialResult(h: String?) { send(h, false) }
                    override fun onResult(h: String?) { send(h, true) }
                    override fun onFinalResult(h: String?) { send(h, true) }
                    override fun onError(e: Exception?) { status("error") }
                    override fun onTimeout() {}
                })
                status("listening")
            } catch (e: Exception) {
                status("error")
            }
            busy = false
        }.start()
    }

    @JavascriptInterface
    fun stop() {
        service?.stop()
        service?.shutdown()
        service = null
        status("stopped")
    }
}
