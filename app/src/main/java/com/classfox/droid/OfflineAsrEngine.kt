package com.classfox.droid

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 端侧离线 ASR(sherpa-onnx 流式 zipformer 中文 int8 模型)。
 * 录音 PCM(16k/mono/f32) → OnlineStream → partial/final 文本回调。
 * 完全离线:不依赖系统识别引擎、厂商 SDK、网络。
 */
class OfflineAsrEngine(
    private val context: Context,
    private val onText: (text: String, isFinal: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "ClassFoxAsr"
        private const val SAMPLE_RATE = 16000
        private const val ASSET_DIR = "zh14m"
        private const val MODEL_NAME = "sherpa-onnx-streaming-zipformer-zh-14M-int8"
        private const val FEATHER = 0.1f // 每次喂入块时长(秒) → 100ms
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var modelDir: File? = null
    private var running = false

    /** 从 assets 拷贝模型到 filesDir(仅首次),返回目录 */
    private fun ensureModel(): File? {
        val dir = File(context.filesDir, MODEL_NAME)
        if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) return dir
        dir.mkdirs()
        val assets = context.assets.list(ASSET_DIR) ?: return null
        for (name in assets) {
            try {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "模型文件就绪: $name")
            } catch (e: Exception) {
                Log.e(TAG, "拷贝模型失败: $name", e)
                return null
            }
        }
        return dir
    }

    /** 初始化识别器;失败返回错误文案 */
    fun init(): String? {
        try {
            val dir = ensureModel() ?: return "模型解包失败"
            val enc = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath
            val dec = File(dir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath
            val join = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath
            val tokens = File(dir, "tokens.txt").absolutePath
            Log.i(TAG, "模型: enc=$enc")
            Log.i(TAG, "文件存在: enc=${File(enc).exists()} dec=${File(dec).exists()} join=${File(join).exists()} tok=${File(tokens).exists()}")

            val transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(enc)
                .setDecoder(dec)
                .setJoiner(join)
                .build()

            val model = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens)
                .setNumThreads(2)
                .setDebug(false)
                .setModelType("zipformer")
                .build()

            val config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(model)
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build()

            val rec = OnlineRecognizer(config)
            recognizer = rec
            stream = rec.createStream()
            running = true
            Log.i(TAG, "离线 ASR 初始化成功")
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "ASR 初始化失败", e)
            return "ASR 初始化失败: ${e.message}"
        }
    }

    /**
     * 喂一段 PCM(16-bit short),转 f32 后进流式识别。
     * @param pcm ShortArray 录音数据
     */
    fun feedPcm(pcm: ShortArray, n: Int) {
        val rec = recognizer ?: return
        val st = stream ?: return
        if (!running) return
        try {
            // short[] → float[](归一化到 [-1,1])
            val floats = FloatArray(n)
            for (i in 0 until n) {
                floats[i] = pcm[i] / 32768.0f
            }
            st.acceptWaveform(floats, SAMPLE_RATE)

            // 300ms 一次，降低字幕/分句延迟；仍由 isReady 门控保护 native 解码
            pendingSamples += n
            if (pendingSamples >= SAMPLE_RATE * 3 / 10) {
                pendingSamples = 0
                if (rec.isReady(st)) {
                    rec.decode(st)
                    val result = rec.getResult(st)
                    val text = result.text.trim()
                    if (text.isNotEmpty()) {
                        Log.i(TAG, "识别中: '$text'")
                        onText(text, isEndpoint(st))
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "识别出错", e)
        }
    }

    private var pendingSamples = 0

    private fun isEndpoint(st: OnlineStream): Boolean {
        return try { recognizer?.isEndpoint(st) == true } catch (_: Throwable) { false }
    }

    /** 停止时冲刷流,输出最终残留 */
    fun finish() {
        running = false
        try {
            val st = stream ?: return
            st.inputFinished()
            recognizer?.decode(st)
            val result = recognizer?.getResult(st)?.text?.trim()
            if (!result.isNullOrEmpty()) onText(result, true)
        } catch (e: Throwable) {
            Log.e(TAG, "finish 出错", e)
        }
    }


    fun release() {
        running = false
        try { stream?.release() } catch (_: Throwable) {}
        try { recognizer?.release() } catch (_: Throwable) {}
        stream = null
        recognizer = null
    }
}
