package com.classfox.droid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 前台录音服务：16kHz/16bit/mono 采音 → RMS 声浪 + WAV 落盘，
 * 识别文本（模拟识别或端侧 sherpa-onnx）统一经 ingestAsrText(text, isFinal) 进入：
 * 分句定稿 → 近重复过滤 → 转录落盘（对齐桌面版格式）→ 两级关键词警报。
 *
 * 事件只走广播（单通道）；音量/计时/文件名走 stateListener（单通道）。
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "ClassFoxRec"
        const val CHANNEL_ID = "classfox_rec"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.classfox.droid.STOP"
        const val ACTION_PAUSE = "com.classfox.droid.PAUSE"
        const val ACTION_RESUME = "com.classfox.droid.RESUME"
        const val EXTRA_COURSE = "course"

        const val SAMPLE_RATE = 16000

        // 事件广播（句子/警报/字幕/状态类文本事件，唯一通道）
        const val ACTION_EVENT = "com.classfox.droid.EVENT"
        const val EXTRA_EVENT = "event"
        const val EXTRA_TEXT = "text"
    }

    inner class RecBinder : Binder() {
        fun service(): RecorderService = this@RecorderService
    }
    private val binder = RecBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ---- 状态 ----
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    fun isActive(): Boolean = running.get()
    fun isPaused(): Boolean = paused.get()
    fun currentCourse(): String = courseName
    fun currentTranscriptFile(): File? = transcriptFile

    private var courseName = ""
    private var startedAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 音频 ----
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var wavFile: File? = null
    private var wavOut: RandomAccessFile? = null
    private var pcmBytes = 0L
    private var dataSizeOffset = 0L
    private var lastRmsDb = -90.0
    private var voiceThresholdDb = -30.0
    private var lastVoiceEventAt = 0L
    private var lastStatePush = 0L

    // ---- 识别引擎（模拟 or 端侧离线，二选一）----
    private var asrEngine: OfflineAsrEngine? = null
    private var mockEngine: MockAsrEngine? = null

    // ---- 分句 / 转录 / 关键词 ----
    private val splitter = SentenceSplitter()
    private var transcriptFile: File? = null
    private var lastCommitted: String? = null
    private var lastCommittedWasClosed = false
    private val recentNorm = ArrayDeque<String>() // 近重复窗口
    private var dangerKeywords: List<String> = emptyList()
    private var warningKeywords: List<String> = emptyList()

    /** 音量/计时/文件名（唯一的状态通道；文本事件一律走广播） */
    var stateListener: ((rmsDb: Double, seconds: Int, file: String?) -> Unit)? = null

    fun setVoiceThreshold(db: Double) { voiceThresholdDb = db.coerceIn(-60.0, -10.0) }
    fun getVoiceThreshold(): Double = voiceThresholdDb

    /** 分句模式切换(0=激进切分, 1=保守合并)，运行中切换即冲刷残留 */
    fun setSplitMode(mode: Int) = splitter.setMode(mode)

    fun reloadKeywords() {
        val (d, w) = Keywords.load(this)
        dangerKeywords = d
        warningKeywords = w
        Log.i(TAG, "词表已加载: 危险${d.size}条 提醒${w.size}条")
    }

    /**
     * 唯一识别文本入口：真实引擎与模拟引擎都喂这里。
     * partial 只更新实时字幕；final/endpoint 才进分句 → 落盘 → 关键词。
     */
    fun ingestAsrText(text: String, isFinal: Boolean) {
        if (!running.get() || paused.get()) return
        val partial = SentenceSplitter.normalize(text)
        if (partial.isNotEmpty()) emit("partial", partial)
        if (!isFinal) return

        val sentences = splitter.feed(text).toMutableList()
        sentences.addAll(splitter.flushRemaining())
        for (s in sentences) commitSentence(s)
    }

    private fun commitSentence(raw: String) {
        val s = SentenceSplitter.normalize(raw)
        if (s.isEmpty() || !SentenceSplitter.isMeaningful(s)) return

        // 激进模式的半句修正：新句是上一句的延伸 → 回写替换，避免流式重复
        val prev = lastCommitted
        if (!lastCommittedWasClosed && prev != null && s != prev &&
            (s.startsWith(prev) || (prev.startsWith(s) && s.length < prev.length))
        ) {
            replaceLastTranscriptLine(prev, s)
            lastCommitted = s
            lastCommittedWasClosed = isClosedSentence(s)
            emit("sentence_corrected", s)
            return
        }

        if (isNearDuplicate(s)) {
            Log.i(TAG, "近重复丢弃: $s")
            return
        }

        // 落盘（对齐桌面版 [HH:MM:SS] 文本）
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        try {
            val f = transcriptFile ?: openTranscriptFile()
            f?.appendText("[$stamp] $s\n")
        } catch (e: Exception) {
            Log.e(TAG, "写转录失败", e)
        }
        lastCommitted = s
        lastCommittedWasClosed = isClosedSentence(s)
        Log.i(TAG, "句: $s")

        // 关键词检测：只查本行，danger 优先于 warning
        val hitD = dangerKeywords.filter { s.contains(it) }
        val hitW = warningKeywords.filter { s.contains(it) }
        if (hitD.isNotEmpty()) {
            Log.i(TAG, "关键词命中(危险): ${hitD.joinToString("、")}")
            emit("danger_alert", "命中警报词：${hitD.joinToString("、")}\n原文：$s")
        } else if (hitW.isNotEmpty()) {
            Log.i(TAG, "关键词命中(提醒): ${hitW.joinToString("、")}")
            emit("warning_alert", "命中提醒词：${hitW.joinToString("、")}\n原文：$s")
        } else {
            emit("sentence", s)
        }
    }

    /** 桌面版对齐的近重复过滤：完全相等 / 包含且长度比≥0.8 / 相似度≥0.88 */
    private fun isNearDuplicate(s: String): Boolean {
        val t = s.trim().trimEnd(*"。，！？!?；;、".toCharArray())
        for (r in recentNorm) {
            if (r == t) return true
            val longer = max(r.length, t.length)
            val shorter = min(r.length, t.length)
            if (longer >= 4 && shorter > 0 && shorter * 10 >= longer * 8 &&
                (r.contains(t) || t.contains(r))
            ) return true
            if (longer >= 6 && lcsRatio(r, t) >= 0.88) return true
        }
        recentNorm.addLast(t)
        if (recentNorm.size > 12) recentNorm.removeFirst()
        return false
    }

    /** SequenceMatcher 风格相似度 = 2*LCS/(lenA+lenB) */
    private fun lcsRatio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var prev = 0
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return 2.0 * dp[b.length] / (a.length + b.length)
    }

    private fun isClosedSentence(s: String): Boolean =
        s.trim().lastOrNull()?.let { it in "。！？!?；;…" } == true

    private fun replaceLastTranscriptLine(oldLine: String, newLine: String) {
        try {
            val f = transcriptFile ?: return
            val all = f.readText()
            val oldFull = Regex("\\[[0-9:]+\\] ${Regex.escape(oldLine)}[\\r\\n]*").find(all)?.value
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            if (oldFull != null) f.writeText(all.replace(oldFull, "[$stamp] $newLine\n"))
            else f.appendText("[$stamp] $newLine\n")
        } catch (e: Exception) {
            Log.e(TAG, "修正转录行失败", e)
        }
    }

    private fun openTranscriptFile(): File? = try {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "classfox")
        if (!dir.exists()) dir.mkdirs()
        val day = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "class_transcript_$day.txt")
        transcriptFile = f
        f.appendText(
            "=== 课堂记录 开始于 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n\n" +
            "课程：${courseName.ifBlank { "未命名课程" }}\n\n"
        )
        f
    } catch (e: Exception) {
        Log.e(TAG, "建转录文件失败", e)
        null
    }

    private fun flushTranscript() {
        for (s in splitter.flushRemaining()) commitSentence(s)
        try {
            transcriptFile?.appendText(
                "\n=== 课堂记录 结束于 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n"
            )
        } catch (_: Exception) {}
    }

    // ---------- 生命周期 ----------
    override fun onCreate() {
        super.onCreate()
        createChannel()
        reloadKeywords()
        voiceThresholdDb = Prefs.get(this).getInt(Prefs.KEY_THRESHOLD, -30).toDouble()
        splitter.setMode(Prefs.splitMode(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused.set(true)
                mockEngine?.setPaused(true)
                emit("paused", "已暂停监听")
                return START_STICKY
            }
            ACTION_RESUME -> {
                paused.set(false)
                mockEngine?.setPaused(false)
                emit("resumed", "已继续监听")
                return START_STICKY
            }
            else -> {
                courseName = intent?.getStringExtra(EXTRA_COURSE)?.trim().orEmpty()
                startRecording()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { mockEngine?.stop() } catch (_: Exception) {}
        try { asrEngine?.release() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { wavOut?.close() } catch (_: Exception) {}
        broadcast("stopped", "录音服务已停止")
        super.onDestroy()
    }

    // ---------- 前台通知 ----------
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "课狐录音监控", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("课狐 ClassFox 正在监听")
            .setContentText(if (courseName.isBlank()) "课堂声音监听中" else "课程：$courseName")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundSafe() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        startForeground(NOTIF_ID, buildNotification())
    }

    // ---------- 录音主逻辑 ----------
    private fun startRecording() {
        if (running.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "无麦克风权限")
            stopSelf()
            return
        }
        startForegroundSafe()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 2, SAMPLE_RATE / 10 * 2)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            rec.release()
            broadcast("error", "录音设备初始化失败")
            stopSelf()
            return
        }
        audioRecord = rec
        stopRequested.set(false)
        paused.set(false)
        running.set(true)
        startedAt = System.currentTimeMillis()
        lastCommitted = null
        lastCommittedWasClosed = false
        recentNorm.clear()
        prepareWavFile()

        // 识别引擎：设置决定用模拟识别还是端侧离线识别（都不阻断录音链路）
        if (Prefs.isMockAsr(this)) {
            val mock = MockAsrEngine { text, final ->
                mainHandler.post { ingestAsrText(text, final) }
            }
            mockEngine = mock
            mock.start()
            emit("asr_status", "[模拟识别] 已启动（可在设置切换为离线识别）")
        } else if (asrEngine == null) {
            val engine = OfflineAsrEngine(this) { text, isFinal ->
                if (text.isNotEmpty()) mainHandler.post { ingestAsrText(text, isFinal) }
            }
            asrEngine = engine
            val err = engine.init()
            if (err != null) {
                Log.e(TAG, "ASR 不可用: $err")
                emit("asr_status", "离线ASR不可用: $err")
            } else {
                emit("asr_status", "离线ASR已就绪(流式中文)")
            }
        } else {
            emit("asr_status", "离线ASR复用已就绪引擎")
        }

        recordThread = Thread({
            val buf = ShortArray(bufSize / 2)
            rec.startRecording()
            Log.i(TAG, "录音开始 course=$courseName")
            while (!stopRequested.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0 && !paused.get()) processChunk(buf, n)
            }
            rec.stop()
            rec.release()
            mockEngine?.stop()
            mockEngine = null
            asrEngine?.finish()
            flushTranscript()
            finalizeWav()
            running.set(false)
            stopRequested.set(false)
            Log.i(TAG, "录音结束")
        }, "classfox-recorder").apply { start() }
    }

    private fun processChunk(buf: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toInt()
            sum += (v * v).toDouble()
        }
        val rms = sqrt(sum / n)
        val db = if (rms < 1e-6) -90.0 else 20.0 * Math.log10(rms / 32768.0)
        lastRmsDb = lastRmsDb * 0.7 + db * 0.3

        try {
            val out = wavOut ?: return
            val bytes = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = buf[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
            pcmBytes += bytes.size
        } catch (e: Exception) {
            Log.e(TAG, "写文件失败", e)
        }

        val now = System.currentTimeMillis()
        if (lastRmsDb > voiceThresholdDb && now - lastVoiceEventAt > 3000) {
            lastVoiceEventAt = now
            emit(
                "voice",
                "检测到较强人声片段（音量 ${"%.0f".format(lastRmsDb)} dB，阈值 ${"%.0f".format(voiceThresholdDb)} dB）"
            )
        }

        if (now - lastStatePush > 250) {
            lastStatePush = now
            val secs = ((now - startedAt) / 1000).toInt()
            val file = wavFile?.absolutePath
            mainHandler.post { stateListener?.invoke(lastRmsDb, secs, file) }
        }
    }

    private fun prepareWavFile() {
        try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "classfox")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "classfox_$stamp.wav")
            val raf = RandomAccessFile(f, "rw")
            raf.write("RIFF".toByteArray())
            raf.write(intLE(0))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(intLE(16))
            raf.write(shortLE(1))
            raf.write(shortLE(1))
            raf.write(intLE(SAMPLE_RATE))
            raf.write(intLE(SAMPLE_RATE * 2))
            raf.write(shortLE(2))
            raf.write(shortLE(16))
            raf.write("data".toByteArray())
            dataSizeOffset = raf.filePointer
            raf.write(intLE(0))
            wavFile = f
            wavOut = raf
            pcmBytes = 0
            Log.i(TAG, "WAV: $f")
        } catch (e: Exception) {
            Log.e(TAG, "建 WAV 失败", e)
        }
    }

    private fun finalizeWav() {
        try {
            val raf = wavOut ?: return
            raf.seek(4); raf.write(intLE((36 + pcmBytes).toInt()))
            raf.seek(dataSizeOffset); raf.write(intLE(pcmBytes.toInt()))
            raf.fd.sync()
            raf.close()
            wavOut = null
            Log.i(TAG, "WAV 完成: $pcmBytes bytes")
        } catch (e: Exception) {
            Log.e(TAG, "finalize WAV 失败", e)
        }
    }

    private fun emit(kind: String, text: String) = broadcast(kind, text)

    private fun broadcast(kind: String, text: String) {
        val i = Intent(ACTION_EVENT)
        i.setPackage(packageName)
        i.putExtra(EXTRA_EVENT, kind)
        i.putExtra(EXTRA_TEXT, text)
        sendBroadcast(i)
    }

    private fun stopRecording() {
        if (!running.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // 让录音线程自然收尾：冲刷最后半句 + 写结束标记 + finalize WAV
        stopRequested.set(true)
        mainHandler.postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 800)
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun shortLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )
}
