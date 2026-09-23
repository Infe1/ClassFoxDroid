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
        const val EXTRA_TEXT_ORIG = "text_orig"
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

    // ---- 识别引擎（模拟 / 系统厂商接口 / 端侧离线，三选一）----
    private var asrEngine: OfflineAsrEngine? = null
    private var mockEngine: MockAsrEngine? = null
    private var systemEngine: SystemAsrEngine? = null

    // ---- 精修通道：SenseVoice + VAD，句末停顿后重转写替换字幕/转录 ----
    private var refineEngine: RefineEngine? = null
    private val refineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val refineBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** onDestroy 后置位：精修引擎异步初始化回主线程时若服务已销毁，直接释放，避免 native 泄漏 */
    @Volatile private var destroyed = false

    // ---- 分句 / 转录 / 关键词 ----
    private val splitter = SentenceSplitter()

    /** 当前分句模式（与 splitter 同步维护；保守模式下流式残留不冲刷，见 ingestAsrText）。
     *  初值在 onCreate 赋（服务字段初始化时 Context 尚未 attach，不能读 Prefs）。 */
    @Volatile private var splitMode = SentenceSplitter.MODE_MERGE_CONSERVATIVE
    private var transcriptFile: File? = null
    private var lastCommitted: String? = null
    private var lastCommittedWasClosed = false
    private val recentNorm = ArrayDeque<String>() // 近重复窗口
    private class RecentLine(val at: Long, val line: String)
    private val recentLines = ArrayDeque<RecentLine>() // 最近转录行（内存，供 AI 救场/进度用）
    private var dangerKeywords: List<String> = emptyList()
    private var warningKeywords: List<String> = emptyList()

    /** 音量/计时/文件名（唯一的状态通道；文本事件一律走广播） */
    var stateListener: ((rmsDb: Double, seconds: Int, file: String?) -> Unit)? = null

    fun setVoiceThreshold(db: Double) { voiceThresholdDb = db.coerceIn(-60.0, -10.0) }
    fun getVoiceThreshold(): Double = voiceThresholdDb

    /** 分句模式切换(0=激进切分, 1=保守合并)，运行中切换即冲刷残留 */
    fun setSplitMode(mode: Int) { splitMode = mode; splitter.setMode(mode) }

    fun reloadKeywords() {
        val (d, w) = Keywords.load(this)
        dangerKeywords = d
        warningKeywords = w
        Log.i(TAG, "词表已加载: 危险${d.size}条 提醒${w.size}条")
    }

    /** 最近 N 分钟的转录行（内存缓存，供 AI 救场/进度） */
    fun recentTranscript(minutes: Int): String = try {
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        synchronized(recentLines) { recentLines.filter { it.at >= cutoff } }
            .joinToString("\n") { it.line }
    } catch (_: Exception) { "" }

    /**
     * 唯一识别文本入口：真实引擎与模拟引擎都喂这里。
     * partial 只更新实时字幕；final/endpoint 才进分句 → 落盘 → 关键词。
     */
    fun ingestAsrText(text: String, isFinal: Boolean) {
        if (!running.get() || paused.get()) return
        val partial = SentenceSplitter.normalize(text)
        if (!isFinal && partial.isNotEmpty()) emit("partial", partial)
        if (!isFinal) return

        val sentences = splitter.feed(text).toMutableList()
        // 仅激进模式冲刷残留。保守模式（v0.10.2 真机重复/乱序根因）：冲刷会把无标点的
        // 流式半句整段写进转录，随后同段音频的精修正文再补一遍 → 内容双份、时间戳乱序。
        // 保守模式的未闭合尾巴交给精修通道（SenseVoice 带标点、按音频时间落盘）。
        if (splitMode == SentenceSplitter.MODE_SPLIT_FAST) sentences.addAll(splitter.flushRemaining())
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
            emit("sentence_corrected", s, prev)
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
        synchronized(recentLines) {
            recentLines.addLast(RecentLine(System.currentTimeMillis(), "[$stamp] $s"))
            while (recentLines.size > 600) recentLines.removeFirst()
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
        }
        // 命中关键词的句子也必须进字幕（警报只是附加提醒，字幕本身必须完整）
        emit("sentence", s)
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
            // 定位【最后一次】出现的带时间戳原句；替换时保留原时间戳（旧逻辑写的是当前时间，会乱序）
            val m = Regex("(\\[[0-9:]+\\]) ${Regex.escape(oldLine)}[\\r\\n]*").findAll(all).lastOrNull()
            if (m != null) {
                f.writeText(all.replaceRange(m.range.first, m.range.last + 1, "${m.groupValues[1]} $newLine\n"))
            } else {
                // 旧版无时间戳前缀的历史行匹配不到 → 按新句追加（时间为当前时间）
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                f.appendText("[$stamp] $newLine\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "修正转录行失败", e)
        }
    }

    /** 课程名 → 安全目录名；空则归入"未命名课程" */
    private fun courseDir(): String {
        val safe = courseName.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_').take(50)
        return safe.ifBlank { "未命名课程" }
    }

    private fun openTranscriptFile(): File? = try {
        val dir = File(getExternalFilesDir(null) ?: filesDir, "classfox/${courseDir()}")
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
        // 激进模式：把分句器里未冲刷的残留句落盘；
        // 保守模式：流式本就未 commit（全文由精修通道写盘），此处冲刷会造成同句双份，只写结束标记。
        if (splitMode == SentenceSplitter.MODE_SPLIT_FAST) {
            for (s in splitter.flushRemaining()) commitSentence(s)
        }
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
        splitMode = Prefs.splitMode(this)
        splitter.setMode(splitMode)
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
                systemEngine?.setPaused(true)
                try { refineEngine?.flushPending() } catch (_: Exception) {}
                refreshNotification()
                emit("paused", "已暂停监听")
                return START_STICKY
            }
            ACTION_RESUME -> {
                paused.set(false)
                mockEngine?.setPaused(false)
                systemEngine?.setPaused(false)
                refreshNotification()
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
        destroyed = true
        running.set(false)
        try { mockEngine?.stop() } catch (_: Exception) {}
        try { systemEngine?.stop() } catch (_: Exception) {}
        try { asrEngine?.release() } catch (_: Exception) {}
        try { refineEngine?.release() } catch (_: Exception) {}
        refineExecutor.shutdownNow()
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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pausePi = PendingIntent.getService(
            this, 2,
            Intent(this, RecorderService::class.java)
                .setAction(if (paused.get()) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("课狐 ClassFox 正在监听")
            .setContentText(
                if (courseName.isBlank()) "课堂声音监听中" else "课程：$courseName"
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
        if (paused.get()) {
            b.addAction(android.R.drawable.ic_media_play, "继续", pausePi)
        } else {
            b.addAction(android.R.drawable.ic_media_pause, "暂停", pausePi)
        }
        b.addAction(android.R.drawable.ic_lock_power_off, "停止", stopPi)
        return b.build()
    }

    private fun refreshNotification() {
        if (running.get()) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun startForegroundSafe() {
        // startForegroundService 启动后必须先 startForeground，否则系统 5s 后判 ANR；
        // 无麦克风权限时再走 stopSelf 收尾
        startForeground(NOTIF_ID, buildNotification())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            broadcast("error", "未授予麦克风权限，无法录音")
            stopSelf()
        }
    }

    // ---------- 录音主逻辑 ----------
    private fun startRecording() {
        if (running.get()) return
        startForegroundSafe()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "无麦克风权限")
            return
        }

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
        synchronized(recentLines) { recentLines.clear() }
        prepareWavFile()
        // 清掉上一场可能残留在 VAD 里的尾音，避免污染新一场的精修分段
        try { refineEngine?.flushPending() } catch (_: Exception) {}

        // 识别引擎：设置三选一（模拟 / 系统厂商接口 / 端侧离线），都不阻断录音链路
        when (Prefs.asrMode(this)) {
            Prefs.ASR_MOCK -> startMockEngine()
            Prefs.ASR_SYSTEM -> startSystemEngine()
            else -> startOfflineEngine()
        }

        // 精修通道（离线识别模式下启用）：SenseVoice 懒加载，失败不影响主链路
        if (Prefs.asrMode(this) == Prefs.ASR_OFFLINE && refineEngine == null) {
            Thread {
                val (eng, err) = RefineEngine.create(this)
                mainHandler.post {
                    if (destroyed) {
                        // 服务已销毁：释放建好的引擎，避免 native 资源泄漏
                        try { eng?.release() } catch (_: Exception) {}
                        return@post
                    }
                    refineEngine = eng
                    emit(
                        "asr_status",
                        if (eng != null) "高精度精修通道已就绪（语句停顿后自动精修）"
                        else "精修通道不可用: $err（实时字幕不受影响）"
                    )
                }
            }.apply { name = "classfox-refine-init" }.start()
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
            mockEngine?.stop(); mockEngine = null
            systemEngine?.stop(); systemEngine = null
            asrEngine?.finish()
            // 引擎 finish 后内部 running=false 不可逆：必须释放并置 null，
            // 否则同一次 App 会话内再次开始会复用到"死引擎"（feedPcm 空跑，无字幕无报错）
            try { asrEngine?.release() } catch (_: Exception) {}
            asrEngine = null
            // 等在跑的精修结束（有界等待），避免最后几句被 onDestroy 的 shutdownNow 打断而丢失
            try { refineExecutor.submit { }.get(2500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            // 收尾落盘放主线程：保证 finish 冲刷出的最后一句话（同样 post 到主线程）先落盘，再写结束标记
            mainHandler.post {
                flushTranscript()
                running.set(false)
            }
            finalizeWav()
            stopRequested.set(false)
            Log.i(TAG, "录音结束")
        }, "classfox-recorder").apply { start() }
    }

    private fun startMockEngine() {
        val mock = MockAsrEngine { text, final ->
            mainHandler.post { ingestAsrText(text, final) }
        }
        mockEngine = mock
        mock.start()
        emit("asr_status", "[模拟识别] 已启动（可在设置切换为其他引擎）")
    }

    private fun startSystemEngine() {
        val sys = SystemAsrEngine(
            this,
            onText = { text, final ->
                mainHandler.post { ingestAsrText(text, final) }
            },
            onStatus = { msg -> emit("asr_status", msg) }
        )
        systemEngine = sys
        sys.start()
        if (sys.running) {
            emit("asr_status", "[系统识别] 已启动（厂商接口 · zh-CN）")
        }
        // 不可用时不回退离线：模拟器等无语音服务环境里，离线解码会 native 崩溃；
        // SystemAsrEngine 已上报明确状态，录音与警报链路照常。
    }

    private fun startOfflineEngine() {
        if (asrEngine != null) {
            emit("asr_status", "离线ASR复用已就绪引擎")
            return
        }
        // 模型解压（首次 160MB+）+ ORT 初始化较慢，移到后台线程，避免冻屏/ANR
        emit("asr_status", "离线ASR初始化中（首次需解压模型，约几秒到十几秒）…")
        Thread {
            val engine = OfflineAsrEngine(this) { text, isFinal ->
                if (text.isNotEmpty()) mainHandler.post { ingestAsrText(text, isFinal) }
            }
            val t0 = System.currentTimeMillis()
            val err = engine.init()
            mainHandler.post {
                if (!running.get()) {
                    // 监听已停止：直接释放，不再挂载
                    try { engine.release() } catch (_: Exception) {}
                    return@post
                }
                if (err != null) {
                    // 初始化失败不登记为可用引擎（否则本场 feedPcm 静默空跑、下一场还复用坏引擎）
                    try { engine.release() } catch (_: Exception) {}
                    Log.e(TAG, "ASR 不可用: $err")
                    emit("asr_status", "离线ASR初始化失败: $err")
                } else {
                    asrEngine = engine
                    val secs = (System.currentTimeMillis() - t0) / 1000
                    emit("asr_status", "离线ASR已就绪（初始化耗时 ${secs} 秒）")
                }
            }
        }.apply { name = "classfox-asr-init" }.start()
    }

    /**
     * 提交一段 VAD 切出的完整语音做高精度重转写。
     * 结果替换"最近一条未闭合的流式句子"：落盘回写 + 广播 sentence_corrected。
     * 单线程串行 + busy 位丢弃积压，保证不拖慢主链路。
     */
    private fun submitRefine(samples: FloatArray) {
        val eng = refineEngine ?: return
        if (!refineBusy.compareAndSet(false, true)) {
            Log.i(TAG, "精修忙，丢弃一段（${samples.size / SAMPLE_RATE}s）")
            return
        }
        val startStamp = System.currentTimeMillis() - samples.size * 1000L / SAMPLE_RATE
        refineExecutor.execute {
            try {
                val text = eng.transcribe(samples)
                if (text.isNotEmpty()) {
                    mainHandler.post { applyRefined(text, startStamp) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "精修失败", e)
            } finally {
                refineBusy.set(false)
            }
        }
    }

    /** 用精修文本替换最近一条近似匹配的流式句子（转录回写 + UI 修正事件） */
    private fun applyRefined(refined: String, startStamp: Long) {
        val s = SentenceSplitter.normalize(refined)
        if (s.isEmpty() || !SentenceSplitter.isMeaningful(s)) return
        val prev = lastCommitted
        // 找最近提交句里与精修结果最相似的一条做替换（前缀/包含/相似度任一命中）
        val replaceable = prev != null && (
            s.startsWith(prev!!.trimEnd('。', '，', ',', '！', '？', '!', '?')) ||
                prev.trimEnd('。', '，', ',', '！', '？', '!', '?').startsWith(s) ||
                isNearSimilar(prev, s)
            )
        if (!replaceable) {
            // 流式漏掉的语句：作为新句补录（orig 空 → UI 追加而非替换）。
            // 保守模式下流式不落盘，这里是唯一写盘机会：必须兜底打开文件、推进 lastCommitted
            // 并做近重复过滤——否则保守模式整段静默不落盘（§2.1），同一句还可能写两次。
            if (isNearDuplicate(s)) {
                Log.i(TAG, "精修补录命中近重复，跳过落盘: $s")
                emit("sentence_corrected", s)
                return
            }
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(startStamp))
            try {
                val f = transcriptFile ?: openTranscriptFile()
                f?.appendText("[$stamp] $s\n")
            } catch (_: Exception) {}
            lastCommitted = s
            lastCommittedWasClosed = true
            Log.i(TAG, "精修补录: $s")
            emit("sentence_corrected", s)
            return
        }
        replaceLastTranscriptLine(prev!!, s)
        lastCommitted = s
        lastCommittedWasClosed = true
        Log.i(TAG, "精修替换: $prev → $s")
        emit("sentence_corrected", s, prev)
    }

    private fun isNearSimilar(a: String, b: String): Boolean {
        val x = a.trimEnd(*"。，！？!?；;、".toCharArray())
        val y = b.trimEnd(*"。，！？!?；;、".toCharArray())
        if (x.isEmpty() || y.isEmpty()) return false
        // 粗粒度字重合率（交集/较短长度），够用来识别"同一句话的两种转写"
        val setX = x.toSet()
        var hit = 0
        for (c in y.toSet()) if (c in setX) hit++
        return hit * 10 >= minOf(x.toSet().size, y.toSet().size) * 7
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

        // 喂流式 ASR（同一份 PCM）
        try {
            asrEngine?.feedPcm(buf, n)
        } catch (_: Exception) {
            // ASR 错误不阻断录音
        }

        // 喂精修通道：VAD 攒段，切出完整语句后后台重转写
        val refine = refineEngine
        if (refine != null) {
            try {
                val segments = refine.feedPcm(buf, n)
                for (seg in segments) submitRefine(seg)
            } catch (_: Exception) {}
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
            val dir = File(getExternalFilesDir(null) ?: filesDir, "classfox/${courseDir()}")
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

    private fun emit(kind: String, text: String, orig: String? = null) = broadcast(kind, text, orig)

    private fun broadcast(kind: String, text: String, orig: String? = null) {
        val i = Intent(ACTION_EVENT)
        i.setPackage(packageName)
        i.putExtra(EXTRA_EVENT, kind)
        i.putExtra(EXTRA_TEXT, text)
        // sentence_corrected 附带被替换的原句：UI 按句匹配替换，避免错行后原句残留双显
        if (orig != null) i.putExtra(EXTRA_TEXT_ORIG, orig)
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
        // 延迟需大于录音线程里"等精修排空"的上限(2500ms)，否则 onDestroy 的 shutdownNow 仍会打断收尾
        }, 2800)
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun shortLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )
}
