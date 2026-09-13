package com.classfox.droid

import android.content.Context
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 真实音频自检：把一段 16 位 PCM WAV 直接喂进端侧离线识别器（不经麦克风），
 * 输出识别文本——用于验证真实语音的识别与分句效果。
 * 注意：模拟器翻译层环境里 zipformer 解码会 native 闪退（环境限制），真机正常。
 */
object AudioSelfTest {

    private const val TAG = "ClassFoxSelfTest"
    private const val TARGET_RATE = 16000

    interface Callbacks {
        fun onLog(msg: String)
        fun onDone(text: String?)
    }

    fun run(context: Context, uri: Uri, cb: Callbacks) {
        Thread {
            var engine: OfflineAsrEngine? = null
            try {
                val pcm = decodeWav(context, uri)
                if (pcm.isEmpty()) {
                    cb.onLog("WAV 解析失败：需要 16 位 PCM 格式")
                    cb.onDone(null)
                    return@Thread
                }
                cb.onLog("音频 ${"%.1f".format(pcm.size / TARGET_RATE.toFloat())} 秒，识别中…")
                Log.i(TAG, "自检开始: ${pcm.size} samples")

                // 与监听服务同一条链路：endpoint final → 分句器 → 定稿句
                val splitter = SentenceSplitter()
                val sb = StringBuilder()
                engine = OfflineAsrEngine(context.applicationContext) { text, isFinal ->
                    if (isFinal) synchronized(sb) {
                        for (s in splitter.feed(text)) sb.append(s).append('\n')
                    } else cb.onLog("…$text")
                }
                val err = engine.init()
                if (err != null) {
                    cb.onLog("识别器初始化失败: $err")
                    cb.onDone(null)
                    return@Thread
                }

                // 以约 4 倍速喂入（每 30ms 喂 100ms 音频），引擎内部按 300ms 节奏解码
                var i = 0
                while (i < pcm.size) {
                    val n = minOf(1600, pcm.size - i)
                    val shorts = ShortArray(n) { j ->
                        (pcm[i + j] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    engine.feedPcm(shorts, n)
                    i += n
                    Thread.sleep(30)
                }
                engine.finish()
                // 收尾：分句器里可能还有未闭合的半句
                synchronized(sb) {
                    for (s in splitter.flushRemaining()) sb.append(s).append('\n')
                }
                engine.release()
                engine = null

                val out = sb.toString().trim()
                Log.i(TAG, "自检完成: $out")
                cb.onDone(out.ifEmpty { "（没有识别到文本）" })
            } catch (e: Throwable) {
                Log.e(TAG, "自检出错", e)
                cb.onLog("自检出错: ${e.message}")
                cb.onDone(null)
            } finally {
                engine?.let { try { it.release() } catch (_: Throwable) {} }
            }
        }.apply { name = "classfox-selftest"; start() }
    }

    /** 解析 WAV（RIFF/16bit PCM），重采样到 16k 单声道，返回 [-1,1] 浮点 */
    private fun decodeWav(ctx: Context, uri: Uri): FloatArray {
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return FloatArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "读文件失败", e)
            return FloatArray(0)
        }
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") return FloatArray(0)

        var channels = 0
        var rate = 0
        var bits = 0
        var data: ByteArray? = null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val sz = bb.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    channels = bb.getShort(pos + 8).toInt()
                    rate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                }
                "data" -> data = bytes.copyOfRange(pos + 8, minOf(pos + 8 + sz, bytes.size))
            }
            pos += 8 + sz + (sz and 1)
        }
        if (data == null || bits != 16 || channels <= 0 || rate <= 0) return FloatArray(0)
        Log.i(TAG, "WAV: ${channels}ch ${rate}Hz ${bits}bit ${data.size}B")

        val frames = data.size / 2 / channels
        // 先抽取/下混为单声道
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val off = (f * channels + c) * 2
                val s = ByteBuffer.wrap(data, off, 2).order(ByteOrder.LITTLE_ENDIAN).short
                sum += s / 32768f
            }
            mono[f] = sum / channels
        }
        // 线性重采样到 16k
        if (rate == TARGET_RATE) return mono
        val outLen = (frames.toLong() * TARGET_RATE / rate).toInt()
        val out = FloatArray(outLen)
        val step = (frames - 1).toFloat() / (outLen - 1).coerceAtLeast(1)
        for (i in 0 until outLen) {
            val p = i * step
            val i0 = p.toInt()
            val frac = p - i0
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            out[i] = mono[i0] * (1 - frac) + mono[i1] * frac
        }
        return out
    }
}
