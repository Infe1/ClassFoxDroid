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
import kotlin.math.abs
import kotlin.math.sqrt
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台录音服务：16kHz/16bit/mono → RMS 声浪 + WAV 落盘。
 * 关键词检测接口已预留（recognition 阶段接入外部 ASR 文本），当前以 RMS 触发事件模拟展示链路。
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "ClassFoxRec"
        const val CHANNEL_ID = "classfox_rec"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.classfox.droid.STOP"
        const val ACTION_PAUSE = "com.classfox.droid.PAUSE"
        const val ACTION_RESUME = "com.classfox.droid.RESUME"

        const val SAMPLE_RATE = 16000

        // 预置"点名/注意"关键词(将来接 ASR 文本后用于匹配)
        val WATCH_KEYWORDS = listOf("点名", "提问", "签到", "作业", "考试", "注意", "重点")

        // 事件广播
        const val ACTION_EVENT = "com.classfox.droid.EVENT"
        const val EXTRA_RMS = "rms"
        const val EXTRA_EVENT = "event"
        const val EXTRA_TEXT = "text"

        // 状态
        const val ACTION_STATE = "com.classfox.droid.STATE"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_FILE = "file"
    }

    // ---- Binder ----
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

    // ---- 分句 + 转录 ----
    private val splitter = SentenceSplitter()
    private var transcriptFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var wavFile: File? = null
    private var wavOut: RandomAccessFile? = null
    private var pcmBytes = 0L
    private var dataSizeOffset = 0L
    private var startedAt = 0L

    // 简易事件(触发式展示)
    private var lastEventAt = 0L
    private var lastRmsDb = -90.0
    private var voiceThresholdDb = -30.0
    fun setVoiceThreshold(db: Double) { voiceThresholdDb = db.coerceIn(-60.0, -10.0) }
    fun getVoiceThreshold(): Double = voiceThresholdDb

    // 更新状态 UI(Activity 绑定后回调)
    var stateListener: ((rmsDb: Double, seconds: Int, event: String?, text: String?, file: String?) -> Unit)? = null

    // 关键词命中回调(Activity 显示警报)
    var keywordListener: ((matched: List<String>, sentence: String) -> Unit)? = null

    /**
     * 对外入口:任何 ASR 引擎(系统 SpeechRecognizer / Vosk / 厂商 SDK)
     * 把识别文本喂进来,经双模式分句器定稿后:写转录文件 + 关键词检测 + UI。
     * @param text     识别文本(partial 或 final 均可)
     * @param isFinal  ASR 是否标记为最终结果
     */
    fun ingestAsrText(text: String, isFinal: Boolean = true) {
        if (!running.get() || paused.get()) return
        val partial = SentenceSplitter.normalize(text)
        if (partial.isNotEmpty()) emit("partial", partial)
        // partial 只更新字幕，不进入落盘/关键词链路；只有 endpoint/final 才提交
        if (!isFinal) return
        val sentences = splitter.feed(text)
        if (sentences.isEmpty()) return
        for (s in sentences) {
            commitSentence(s)
        }
        if (isFinal) {
            // final 结果:激进模式下把残留也收掉
            val tail = splitter.flushRemaining()
            for (s in tail) commitSentence(s)
        }
    }

    /** 模式切换(0=激进切分,1=保守合并),切换时冲刷残留 */
    fun setSplitMode(mode: Int) {
        splitter.setMode(mode)
    }

    // 最后落盘句(供激进模式延伸修正:半句被后续延伸时替换,避免重复)
    private var lastCommitted: String? = null
    private var lastCommittedWasClosed = false

    private fun commitSentence(raw: String) {
        val s = SentenceSplitter.normalize(raw)
        if (s.isEmpty() || !SentenceSplitter.isMeaningful(s)) return

        // 激进模式的半句修正:新句是上一句的延伸(旧句无闭合标点) → 替换上一句
        val prev = lastCommitted
        if (!lastCommittedWasClosed && prev != null && s != prev &&
            (s.startsWith(prev) || prev.startsWith(s) && s.length < prev.length)
        ) {
            replaceLastTranscriptLine(prev, s)
            lastCommitted = s
            emit("sentence_corrected", s)
            return
        }

        // 1. 落盘转录(格式对齐桌面: [HH:MM:SS] 文本)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$stamp] $s\n"
        try {
            val f = transcriptFile ?: openTranscriptFile()
            if (f != null) {
                f.appendText(line)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写转录失败", e)
        }
        lastCommitted = s
        lastCommittedWasClosed = isClosedSentence(s)
        Log.i(TAG, "句: $s")

        // 2. 关键词检测(点名/提问等 → 红色警报)
        val dangerKeywords = setOf("点名", "提问", "签到")
        val matched = WATCH_KEYWORDS.filter { s.contains(it) }
        if (matched.isNotEmpty()) {
            Log.i(TAG, "关键词命中: ${matched.joinToString("、")}")
            val danger = matched.any { it in dangerKeywords }
            val kind = if (danger) "danger_alert" else "warning_alert"
            val icon = if (danger) "🚨" else "⚠️"
            emit(kind, "$icon 命中:${matched.joinToString("、")} → $s")
            keywordListener?.invoke(matched, s)
        } else {
            emit("sentence", s)
        }
    }

    /** 句末是否闭合(。！？!?；;…… 结尾) */
    private fun isClosedSentence(s: String): Boolean =
        s.trim().lastOrNull()?.let { it in "。！？!?；;…" } == true

    /** 回写替换转录文件最后一行(激进模式半句修正) */
    private fun replaceLastTranscriptLine(oldLine: String, newLine: String) {
        try {
            val f = transcriptFile ?: return
            val all = f.readText()
            val oldFull = Regex("\\[[0-9:]+\\] ${Regex.escape(oldLine)}[\\r\\n]*").find(all)?.value
            if (oldFull != null) {
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                f.writeText(all.replace(oldFull, "[$stamp] $newLine\n"))
            } else {
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                f.appendText("[$stamp] $newLine\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "修正转录行失败", e)
        }
    }

    private fun openTranscriptFile(): File? {
        return try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "classfox")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "class_transcript_$stamp.txt")
            transcriptFile = f
            // 会话标记(对齐桌面: 课堂记录 开始于 …)
            if (!f.exists()) {
                f.appendText("=== 课堂记录 开始于 $stamp ===\n")
            }
            f
        } catch (e: Exception) {
            Log.e(TAG, "建转录文件失败", e)
            null
        }
    }

    /** 停止时冲刷分句器残留(最后半句也落盘) */
    private fun flushTranscript() {
        val tail = splitter.flushRemaining()
        for (s in tail) commitSentence(s)
        try {
            transcriptFile?.appendText("=== 课堂记录 结束 ===\n")
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            paused.set(true)
            emit("paused", "⏸ 已暂停监听")
            return START_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            paused.set(false)
            emit("resumed", "▶️ 已继续监听")
            return START_STICKY
        }

        startRecording()
        return START_STICKY
    }

    // ---------- 前台通知 ----------
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "课狐录音监控", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("课狐 ClassFox 移动端")
            .setContentText("正在听课堂声音 · 离线中文识别")
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
            return
        }
        startForegroundSafe()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 2, SAMPLE_RATE / 10 * 2) // >= 100ms
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            rec.release()
            return
        }
        audioRecord = rec
        stopRequested.set(false)
        paused.set(false)
        running.set(true)
        startedAt = System.currentTimeMillis()
        prepareWavFile()

        // 初始化离线 ASR:懒加载,只 init 一次(避免反复 release/init 竞争崩溃)
        if (asrEngine == null) {
            val engine = OfflineAsrEngine(this) { text, isFinal ->
                // 识别回调:喂入分句器 → 落盘/关键词/UI(识别线程回调,转主线程)
                if (text.isNotEmpty()) {
                    mainHandler.post { ingestAsrText(text, isFinal) }
                }
            }
            asrEngine = engine
            val err = engine.init()
            if (err != null) {
                Log.e(TAG, "ASR 不可用: $err")
                emit("voice", "⚠️ 离线ASR不可用: $err")
            } else {
                emit("voice", "🎙 离线ASR已就绪(流式中文)")

            }
        } else {
            emit("voice", "🎙 离线ASR复用已就绪引擎")
        }

        recordThread = Thread({
            val buf = ShortArray(bufSize / 2)
            rec.startRecording()
            Log.i(TAG, "录音开始")
            while (!stopRequested.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    if (!paused.get()) processChunk(buf, n)
                }
            }
            rec.stop()
            rec.release()
            asrEngine?.finish()
            flushTranscript()
            finalizeWav()
            running.set(false)
            stopRequested.set(false)
            Log.i(TAG, "录音结束")
        }, "classfox-recorder").apply { start() }
    }

    private var asrEngine: OfflineAsrEngine? = null

    private fun processChunk(buf: ShortArray, n: Int) {
        // RMS
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toInt()
            sum += (v * v).toDouble()
        }
        val rms = sqrt(sum / n)
        val db = if (rms < 1e-6) -90.0 else 20.0 * Math.log10(rms / 32768.0)
        // 平滑
        lastRmsDb = lastRmsDb * 0.7 + db * 0.3

        // 写 PCM
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

        // 喂给离线 ASR(同一份 PCM)
        try {
            asrEngine?.feedPcm(buf, n)
        } catch (e: Exception) {
            // ASR 错误不阻断录音
        }

        // 仅记录真正较响的人声，默认阈值 -30dB，可由设置页调用 setVoiceThreshold 调整
        val now = System.currentTimeMillis()
        if (lastRmsDb > voiceThresholdDb && now - lastEventAt > 3000) {
            lastEventAt = now
            emit("voice", "检测到较强人声片段（音量 ${"%.0f".format(lastRmsDb)} dB，阈值 ${"%.0f".format(voiceThresholdDb)} dB）")
        }

        // 每 250ms 推送一次状态
        val secs = ((now - startedAt) / 1000).toInt()
        if (now - lastStatePush > 250) {
            lastStatePush = now
            mainHandler.post {
                stateListener?.invoke(lastRmsDb, secs, null, null, wavFile?.absolutePath)
            }
        }
    }

    private var lastStatePush = 0L

    private fun prepareWavFile() {
        try {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir, "classfox")
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "classfox_$stamp.wav")
            val raf = RandomAccessFile(f, "rw")
            // WAV 头(先占位,结束时回填)
            raf.write("RIFF".toByteArray())
            raf.write(intLE(0)) // riff size
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(intLE(16))
            raf.write(shortLE(1))      // PCM
            raf.write(shortLE(1))      // mono
            raf.write(intLE(SAMPLE_RATE))
            raf.write(intLE(SAMPLE_RATE * 2))
            raf.write(shortLE(2))      // block align
            raf.write(shortLE(16))     // bits
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
            Log.i(TAG, "WAV 完成: ${pcmBytes} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "finalize WAV 失败", e)
        }
    }

    private fun emit(kind: String, text: String) {
        val broadcast = Intent(ACTION_EVENT)
        broadcast.setPackage(packageName)
        broadcast.putExtra(EXTRA_EVENT, kind)
        broadcast.putExtra(EXTRA_TEXT, text)
        sendBroadcast(broadcast)
        // 事件统一走广播,避免 stateListener + 广播造成同一条日志显示两次
        if (kind == "voice") {
            mainHandler.post { stateListener?.invoke(lastRmsDb, secondsNow(), kind, text, wavFile?.absolutePath) }
        }
    }

    private fun secondsNow(): Int = ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    private fun stopRecording() {
        if (!running.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // 先让录音线程自然收尾,避免最后一段 ASR/转录排队任务被 running=false 丢弃
        stopRequested.set(true)
        mainHandler.postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 800)
    }
    override fun onDestroy() {
        running.set(false)
        try { asrEngine?.release() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { wavOut?.close() } catch (_: Exception) {}
        // 通知 UI:已停止(避免绑定服务 stopSelf 后 Activity 状态不同步)
        val b = Intent(ACTION_EVENT)
        b.setPackage(packageName)
        b.putExtra(EXTRA_EVENT, "stopped")
        b.putExtra(EXTRA_TEXT, "录音服务已停止")
        sendBroadcast(b)
        super.onDestroy()
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun shortLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )
}
