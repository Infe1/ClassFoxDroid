package com.classfox.droid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 系统识别引擎（厂商接口）：android.speech.SpeechRecognizer。
 * 各厂商真机背后通常是调优过的中文识别引擎（讯飞/自研），不占 APK 体积，
 * 但行为碎片化：部分引擎忽略 partial/静音参数，甚至不支持连续听写。
 *
 * 策略：单次听写 → 结果后自动重启形成连续链路；错误按指数退避重启，
 * 60s 内连续失败过多则自动停机并上报，避免无限循环耗电。
 * 所有回调已在主线程；文本经 onText(text, isFinal) 进入与服务内模拟/离线
 * 引擎完全相同的 ingestAsrText 入口。
 */
class SystemAsrEngine(
    private val context: Context,
    private val onText: (text: String, isFinal: Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "ClassFoxSysAsr"
        private const val RESTART_DELAY_MS = 150L
        private const val MAX_ERROR_BACKOFF_MS = 4000L
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val MAX_FAILURES_IN_WINDOW = 10
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile var running = false
        private set

    /** 暂停时停掉当前听写；继续时重新发起 */
    @Volatile var paused = false
        private set

    private var consecutiveErrors = 0
    private val failureTimestamps = ArrayDeque<Long>()
    private var inSession = false // 一次 startListening 到 results/error 之间

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("系统识别不可用（此设备没有系统/厂商语音服务），请改用离线识别或模拟识别")
            return
        }
        running = true
        consecutiveErrors = 0
        failureTimestamps.clear()
        // createSpeechRecognizer 要求主线程；service 的 startRecording 本就在主线程
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        Log.i(TAG, "系统识别启动（厂商接口）")
        startListening()
    }

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) {
            try { recognizer?.stopListening() } catch (_: Exception) {}
        } else if (running) {
            mainHandler.post { startListening() }
        }
    }

    fun stop() {
        running = false
        inSession = false
        mainHandler.post {
            try { recognizer?.stopListening() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
        }
        Log.i(TAG, "系统识别停止")
    }

    private fun startListening() {
        if (!running || paused) return
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 句中静音 ~2s 定稿一段，贴近桌面版 2.4s 分句节奏
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        inSession = true
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening 失败", e)
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (!running) return
        consecutiveErrors++
        val delay = if (consecutiveErrors <= 1) RESTART_DELAY_MS
        else minOf(RESTART_DELAY_MS * (1L shl minOf(consecutiveErrors, 4)), MAX_ERROR_BACKOFF_MS)
        mainHandler.postDelayed({ startListening() }, delay)
    }

    /** 60s 窗口内失败过多 → 自动停机（防耗电死循环） */
    private fun recordFailureAndMaybeAbort(): Boolean {
        val now = System.currentTimeMillis()
        failureTimestamps.addLast(now)
        while (failureTimestamps.isNotEmpty() && now - failureTimestamps.first() > FAILURE_WINDOW_MS) {
            failureTimestamps.removeFirst()
        }
        if (failureTimestamps.size >= MAX_FAILURES_IN_WINDOW) {
            running = false
            inSession = false
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            onStatus("系统识别反复失败（60 秒内 $MAX_FAILURES_IN_WINDOW 次），已自动停止；请检查设备语音服务或改用其他引擎")
            return true
        }
        return false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            inSession = false
            Log.w(TAG, "onError: $error")
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onStatus("系统识别缺麦克风权限，请到系统设置授予")
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // 正常的静音超时，静默重启即可
                    if (!recordFailureAndMaybeAbort()) scheduleRestart()
                }
                else -> {
                    if (!recordFailureAndMaybeAbort()) scheduleRestart()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            inSession = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                Log.i(TAG, "final: $text")
                onText(text, true)
            }
            // 连续听写：结果即重启下一段
            if (running) mainHandler.postDelayed({ startListening() }, RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) onText(text, false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
