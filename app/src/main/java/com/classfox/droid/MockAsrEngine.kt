package com.classfox.droid

import android.util.Log

/**
 * 模拟识别引擎（演示/链路验证用）：完全不触碰真实 ASR native 库，
 * 按课堂脚本逐句产出 partial(渐进前缀) → final(整句)，喂入与真实识别
 * 完全相同的 ingestAsrText(text, isFinal) 入口，用于在模拟器上验证
 * 分句 → 转录落盘 → 关键词警报 → UI 全链路。
 * 真实设备请在「设置」里切回离线识别（sherpa-onnx）。
 */
class MockAsrEngine(private val onText: (text: String, isFinal: Boolean) -> Unit) {

    companion object {
        private const val TAG = "ClassFoxMock"
        private val SCRIPT = listOf(
            "同学们，我们开始上课，今天讲第三章函数的单调性。",
            "先看这道例题，大家注意定义域。",
            "重点来了，这个判定方法期末必考，记到笔记本上。",
            "这一步很容易出错，谁能说说为什么？",
            "我找一位同学上来回答这个问题。",
            "很好，请坐。注意这里不能漏掉等号成立的条件。",
            "课后作业是习题三点一到三点五，下节课检查。",
            "另外提醒一下，下周有小测，范围是前两章。"
        )
        private const val CHUNK_MS = 350L
        private const val CHUNK_CHARS = 4
        private const val LINE_GAP_MS = 1500L
    }

    @Volatile private var running = false
    @Volatile var paused = false
        private set
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        paused = false
        thread = Thread {
            Log.i(TAG, "模拟识别启动")
            var i = 0
            while (running) {
                val line = SCRIPT[i % SCRIPT.size]
                i++
                var shown = 0
                while (running && shown < line.length) {
                    shown = minOf(line.length, shown + CHUNK_CHARS)
                    if (!paused) onText(line.substring(0, shown), false)
                    sleepInterruptible(CHUNK_MS)
                }
                if (!running) break
                if (!paused) onText(line, true)
                var waited = 0L
                while (running && waited < LINE_GAP_MS) {
                    sleepInterruptible(100)
                    waited += 100
                }
            }
            Log.i(TAG, "模拟识别结束")
        }.apply {
            name = "classfox-mock-asr"
            start()
        }
    }

    fun setPaused(p: Boolean) { paused = p }

    fun stop() { running = false; thread?.interrupt() }  // interrupt 让 sleep 立即退（sleepInterruptible 会顺带清 running）

    private fun sleepInterruptible(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false }
    }
}
