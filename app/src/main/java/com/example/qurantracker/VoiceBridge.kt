package com.example.qurantracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class VoiceBridge(private val act: Activity, private val web: WebView) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var model: WhisperModel? = null
    private val rate = 16000
    private val keep = rate * 3
    private val ring = ShortArray(keep)
    private val lock = Any()
    private var wpos = 0
    private var total = 0L

    private fun js(code: String) { web.post { web.evaluateJavascript(code, null) } }
    private fun status(s: String) { js("window.onVoiceStatus&&onVoiceStatus('" + s + "')") }
    private fun send(t: String) {
        if (t.isBlank()) return
        js("window.onVoiceText&&onVoiceText(" + JSONObject.quote(t) + ",true)")
    }

    private fun snapshot(): ShortArray = synchronized(lock) {
        val n = minOf(total, keep.toLong()).toInt()
        val out = ShortArray(n)
        val start = (wpos - n + keep) % keep
        for (i in 0 until n) out[i] = ring[(start + i) % keep]
        out
    }

    private fun rms(p: ShortArray, last: Int): Double {
        val n = minOf(last, p.size)
        if (n == 0) return 0.0
        var s = 0.0
        for (i in p.size - n until p.size) { val v = p[i].toDouble(); s += v * v }
        return sqrt(s / n)
    }

    private fun writeWav(f: File, p: ShortArray) {
        val len = p.size * 2
        val h = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + len); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(len)
        for (v in p) h.putShort(v)
        FileOutputStream(f).use { it.write(h.array()) }
    }

    private suspend fun loadModel(): WhisperModel {
        val dst = File(act.filesDir, "quran-base-q5_1.bin")
        if (!dst.exists() || dst.length() < 50_000_000L) {
            dst.delete()
            val tmp = File(act.filesDir, "quran-base-q5_1.tmp")
            act.assets.open("models/quran-base-q5_1.bin").use { i ->
                FileOutputStream(tmp).use { o -> i.copyTo(o) }
            }
            tmp.renameTo(dst)
        }
        return Whisper.loadModel(act, dst.absolutePath)
    }

    @JavascriptInterface
    fun start() {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77) }
            status("perm")
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            var rec: AudioRecord? = null
            try {
                status("loading")
                val m = model ?: loadModel().also { model = it }
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
                rec = r
                synchronized(lock) { wpos = 0; total = 0L }
                r.startRecording()
                val reader = launch(Dispatchers.IO) {
                    val buf = ShortArray(1600)
                    while (isActive) {
                        val n = r.read(buf, 0, buf.size)
                        if (n > 0) {
                            synchronized(lock) {
                                for (i in 0 until n) { ring[wpos] = buf[i]; wpos = (wpos + 1) % keep }
                                total += n
                            }
                        } else if (n < 0) break
                    }
                }
                status("listening")
                val wav = File(act.cacheDir, "w.wav")
                var last = 0L
                while (isActive) {
                    delay(100)
                    val t = synchronized(lock) { total }
                    if (t - last < rate / 2) continue
                    last = t
                    val pcm = snapshot()
                    if (pcm.size < rate) continue
                    if (rms(pcm, rate * 6 / 10) < 300.0) continue
                    writeWav(wav, pcm)
                    val res = Whisper.transcribe(m, wav.absolutePath, WhisperConfig(language = "ar", threads = 4, printTimestamps = false))
                    send(res.text)
                }
                reader.cancel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                status("error")
            } finally {
                try { rec?.stop() } catch (e: Exception) {}
                rec?.release()
            }
        }
    }

    @JavascriptInterface
    fun stop() {
        job?.cancel()
        job = null
        status("stopped")
    }
}
