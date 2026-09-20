package com.classfox.droid

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import java.io.File

/**
 * 高精度重转写引擎（双通道的"精修"通道）：
 * SenseVoice int8（非流式，带标点 + 逆文本规整）+ Silero VAD 分段。
 * 流式通道出低延迟字幕；检测到语句停顿（VAD 静音）后，把该段音频交给
 * 本引擎重转写，用带标点的更准文本替换字幕并落盘——课狐与讯飞同款架构。
 */
class RefineEngine private constructor(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad
) {
    companion object {
        private const val TAG = "ClassFoxRefine"
        private const val SV_DIR = "sherpa-onnx-sense-voice-int8-2024-07-17"
        private const val SAMPLE_RATE = 16000

        /** 从 assets 解压模型（仅首次），初始化失败返回 null 并给出错误文案 */
        fun create(context: Context, onStatus: (String) -> Unit = {}): Pair<RefineEngine?, String?> {
            return try {
                val dir = ensureModels(context)
                val t0 = System.currentTimeMillis()
                val cfg = OfflineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder().build())
                    .setOfflineModelConfig(
                        OfflineModelConfig.builder()
                            .setSenseVoice(
                                OfflineSenseVoiceModelConfig.builder()
                                    .setModel(File(dir, "model.int8.onnx").absolutePath)
                                    .setLanguage("zh")
                                    .setInverseTextNormalization(true)
                                    .build()
                            )
                            .setTokens(File(dir, "tokens.txt").absolutePath)
                            .setNumThreads(2)
                            .setModelType("sense-voice")
                            .build()
                    )
                    .build()
                val rec = OfflineRecognizer(cfg)
                val vad = Vad(
                    VadModelConfig.builder()
                        .setSileroVadModelConfig(
                            SileroVadModelConfig.builder()
                                .setModel(ensureVad(context).absolutePath)
                                .setThreshold(0.5f)
                                .setMinSilenceDuration(0.75f)
                                .setWindowSize(512)
                                .build()
                        )
                        .setSampleRate(SAMPLE_RATE)
                        .build()
                )
                Log.i(TAG, "SenseVoice+VAD 就绪，耗时 ${(System.currentTimeMillis() - t0) / 1000.0}s")
                Pair(RefineEngine(rec, vad), null)
            } catch (e: Throwable) {
                Log.e(TAG, "RefineEngine 初始化失败", e)
                Pair(null as RefineEngine?, "SenseVoice 初始化失败: ${e.message}")
            }
        }

        private fun ensureModels(context: Context): File {
            val dir = File(context.filesDir, SV_DIR)
            if (File(dir, "model.int8.onnx").exists() && File(dir, "model.int8.onnx").length() > 0) return dir
            dir.mkdirs()
            for (name in listOf("model.int8.onnx", "tokens.txt")) {
                context.assets.open("sensevoice/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
                Log.i(TAG, "解压: $name")
            }
            return dir
        }

        private fun ensureVad(context: Context): File {
            val f = File(context.filesDir, "silero_vad.onnx")
            if (f.exists() && f.length() > 0) return f
            context.assets.open("vad/silero_vad.onnx").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            return f
        }
    }

    /** 喂一段 PCM（16bit short），VAD 攒段；返回本次切出的完整语音段（可能 0 个） */
    fun feedPcm(pcm: ShortArray, n: Int): List<FloatArray> {
        val floats = FloatArray(n) { i -> pcm[i] / 32768f }
        vad.acceptWaveform(floats)
        val out = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            out.add(seg.samples)
        }
        return out
    }

    /** 丢弃 VAD 内部缓冲（暂停时用），避免暂停期间的话音拖到继续后 */
    fun flushPending() {
        vad.flush()
        while (!vad.empty()) vad.pop()
    }

    /** 重转写一段音频（阻塞，后台线程调用） */
    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        return text
    }

    fun release() {
        try { recognizer.release() } catch (_: Throwable) {}
        try { vad.release() } catch (_: Throwable) {}
    }
}
