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
        private const val FEATHER = 0.1f // 每次喂入块时长(秒) → 100ms

        // 两套内置模型：小=体积小但真实语音精度差；大=encoder 153MB，精度高
        private data class ModelSpec(
            val assetDir: String, val dirName: String,
            val encoder: String, val decoder: String, val joiner: String
        )
        private val MODEL_SMALL = ModelSpec(
            "zh14m", "sherpa-onnx-streaming-zipformer-zh-14M-int8",
            "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx"
        )
        private val MODEL_LARGE = ModelSpec(
            "zh2025", "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx"
        )
    }

    private val spec: ModelSpec =
        if (Prefs.asrModel(context) == Prefs.MODEL_SMALL) MODEL_SMALL else MODEL_LARGE

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var modelDir: File? = null
    private var running = false

    /** 从 assets 拷贝模型到 filesDir(仅首次),返回目录 */
    private fun ensureModel(): File? {
        val dir = File(context.filesDir, spec.dirName)
        val enc = File(dir, spec.encoder)
        if (enc.exists() && enc.length() > 0) return dir
        dir.mkdirs()
        val assets = context.assets.list(spec.assetDir) ?: return null
        for (name in assets) {
            try {
                context.assets.open("${spec.assetDir}/$name").use { input ->
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
    /**
     * 初始化；rule1SilenceSec = 句末静音断句阈值（秒），默认 2.0。
     * 提为参数供精度实验用（诊断广播传不同值对比），服务端不传即默认。
     */
    fun init(rule1SilenceSec: Float = 2.0f): String? {
        try {
            val dir = ensureModel() ?: return "模型解包失败"
            val enc = File(dir, spec.encoder).absolutePath
            val dec = File(dir, spec.decoder).absolutePath
            val join = File(dir, spec.joiner).absolutePath
            val tokens = File(dir, "tokens.txt").absolutePath
            Log.i(TAG, "模型[${spec.assetDir}]: enc=$enc")
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
                .setModelType(if (spec == MODEL_LARGE) "zipformer2" else "zipformer")
                .build()

            val config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(model)
                .setEnableEndpoint(true)
                // 课堂停顿调优 v0.10.1：老师句中换气/短停顿(0.5~1s)不再被误判为句末，
                // 需要 2.0s 连续静音才定稿 → 单句更长、上下文更完整、SenseVoice 精修段落更准。
                // 代价：字幕定稿延迟约 +0.8s（partial 实时字仍即时刷新，不影响观感）。
                // 硬上限 30s 防止长段积压。
                .setEndpointConfig(
                    com.k2fsa.sherpa.onnx.EndpointConfig.builder()
                        .setRule1(
                            com.k2fsa.sherpa.onnx.EndpointRule.builder()
                                .setMustContainNonSilence(true)
                                .setMinTrailingSilence(rule1SilenceSec)
                                .build()
                        )
                        .setRule2(
                            com.k2fsa.sherpa.onnx.EndpointRule.builder()
                                .setMustContainNonSilence(false)
                                .setMinTrailingSilence(1.5f)
                                .build()
                        )
                        .setRule3(
                            com.k2fsa.sherpa.onnx.EndpointRule.builder()
                                .setMinUtteranceLength(30f)
                                .build()
                        )
                        .build()
                )
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

            // 200ms 一次解码尝试：isReady 门控下不空转；兼顾字幕延迟与 CPU 占用
            pendingSamples += n
            if (pendingSamples >= SAMPLE_RATE * 2 / 10) {
                pendingSamples = 0
                if (rec.isReady(st)) {
                    rec.decode(st)
                    val result = rec.getResult(st)
                    val text = result.text.trim()
                    val endpoint = isEndpoint(st)
                    if (text.isNotEmpty()) {
                        Log.i(TAG, "识别中: '$text'")
                        onText(text, endpoint)
                    }
                    if (endpoint) {
                        // 关键：endpoint 后必须重置流，否则上下文持续累积，
                        // 后续每次结果都会携带之前全部文本（表现为"识别越滚越长"的重复）
                        try { rec.reset(st) } catch (e: Throwable) { Log.e(TAG, "reset 失败", e) }
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
            val rec = recognizer ?: return
            val st = stream ?: return
            // 仅在确有待解码内容时冲刷：空冲刷也会触发解码，
            // 在模拟器翻译层环境会导致 native exit(255)（进程闪退）
            if (rec.isReady(st)) {
                st.inputFinished()
                // 循环解码直到流内不再有可解码内容（单次 decode 可能消化不完）
                var guard = 0
                while (rec.isReady(st) && guard++ < 200) rec.decode(st)
                val result = recognizer?.getResult(st)?.text?.trim()
                if (!result.isNullOrEmpty()) onText(result, true)
            } else {
                Log.i(TAG, "finish: 无待解码内容，跳过冲刷")
            }
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
