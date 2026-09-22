package com.classfox.droid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实验用诊断广播（精度调参工具，非用户功能）：
 *   adb shell am broadcast -f 0x1000000 -a com.classfox.droid.DIAG_ASR \
 *     --es wav /path/x.wav --es out /path/out.txt \
 *     --ef silence 3.0 --ei mode 1 --ei refine 1 --ei stream 0 \
 *     --ef vadsil 1.2 --ef vadthr 0.5
 * （-f 0x1000000 = FLAG_RECEIVER_FOREGROUND，绕过后台广播限制；app 需已启动）
 *
 * 把指定 WAV 流式跑链路并落盘：
 *   [流式] OfflineAsrEngine(rule1=silence) → SentenceSplitter(mode) → 定稿句
 *   [精修] RefineEngine(SenseVoice+VAD, vadsil/vadthr) 全段批量转写，
 *          每段带音频起始时间戳（与转录文件同格式，便于分时段对齐评分）
 * 只读入参 WAV、写出参文件，不影响监听服务。16bit PCM 16k 单声道直通。
 */
class DiagAsrReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ClassFoxDiag"
        private const val ACTION = "com.classfox.droid.DIAG_ASR"
        private const val TARGET_RATE = 16000
        @Volatile private var busy = false
    }

    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ACTION || busy) return
        val wavPath = i.getStringExtra("wav") ?: return
        val outPath = i.getStringExtra("out")
            ?: (ctx.getExternalFilesDir(null)!!.absolutePath + "/diag_out.txt")
        val silence = i.getFloatExtra("silence", 2.0f)
        val mode = i.getIntExtra("mode", SentenceSplitter.MODE_MERGE_CONSERVATIVE)
        val doRefine = i.getIntExtra("refine", 1) == 1
        val doStream = i.getIntExtra("stream", 1) == 1
        val vadSil = i.getFloatExtra("vadsil", 0.75f)
        val vadThr = i.getFloatExtra("vadthr", 0.5f)
        busy = true
        // 不用 goAsync：工作在自有线程，onReceive 立即返回可避免广播超时 ANR；
        // 进程存活期间线程自然跑完（实验期间保持 app 前台更稳）
        Thread {
            val sb = StringBuilder()
            try {
                val wav = File(wavPath)
                val header = parseWavHeader(wav)
                if (header == null || header.second != TARGET_RATE || header.third != 1) {
                    write(outPath, "WAV 需 16k 单声道 16bit PCM（实际: $header）"); return@Thread
                }
                val dataOff = header.first
                val frames = ((wav.length() - dataOff) / 2).toInt()
                sb.appendLine("=== silence=$silence mode=$mode vadsil=$vadSil vadthr=$vadThr 音频=${frames / TARGET_RATE}s ===")

                if (doStream) {
                    val splitter = SentenceSplitter(mode)
                    val streamOut = StringBuilder()
                    val eng = OfflineAsrEngine(ctx.applicationContext) { text, isFinal ->
                        if (isFinal) synchronized(streamOut) {
                            for (s in splitter.feed(text)) streamOut.append(s).append('\n')
                        }
                    }
                    val err = eng.init(silence)
                    if (err != null) {
                        sb.appendLine("[流式] 初始化失败: $err")
                    } else {
                        feedBlocks(wav, dataOff, frames) { shorts, n, _ -> eng.feedPcm(shorts, n) }
                        eng.finish()
                        synchronized(streamOut) {
                            for (s in splitter.flushRemaining()) streamOut.append(s).append('\n')
                        }
                        eng.release()
                        sb.appendLine("[流式]"); sb.append(streamOut)
                    }
                }

                if (doRefine) {
                    val refineOut = StringBuilder()
                    val re = RefineEngine.create(ctx.applicationContext, { }, vadSil, vadThr).first
                    if (re == null) {
                        sb.appendLine("[精修] 初始化失败")
                    } else {
                        feedBlocks(wav, dataOff, frames) { shorts, n, offSec ->
                            for (seg in re.feedPcm(shorts, n)) {
                                val t = re.transcribe(seg).trim()
                                if (t.isNotBlank()) {
                                    val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
                                        .format(Date(offSec * 1000L))
                                    refineOut.append("[$stamp] $t\n")
                                }
                            }
                        }
                        re.flushPending()
                        re.release()
                        sb.appendLine("[精修]"); sb.append(refineOut)
                    }
                }
                write(outPath, sb.toString())
            } catch (e: Throwable) {
                Log.e(TAG, "diag 失败", e)
                write(outPath, "诊断失败: ${e.message}")
            } finally {
                busy = false
            }
        }.apply { name = "classfox-diag-asr"; start() }
    }

    /** RIFF 头 → (data 偏移, 采样率, 声道数)，仅支持 16bit */
    private fun parseWavHeader(f: File): Triple<Long, Int, Int>? {
        if (f.length() < 44) return null
        RandomAccessFile(f, "r").use {
            val head = ByteArray(12); it.readFully(head)
            if (String(head, 0, 4) != "RIFF") return null
            var rate = 0; var ch = 0
            while (true) {
                val chunk = ByteArray(8)
                if (it.read(chunk) < 8) return null
                val id = String(chunk, 0, 4)
                val sz = java.lang.Integer.toUnsignedLong(
                    (chunk[4].toInt() and 0xFF) or ((chunk[5].toInt() and 0xFF) shl 8) or
                        ((chunk[6].toInt() and 0xFF) shl 16) or ((chunk[7].toInt() and 0xFF) shl 24)
                )
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(minOf(sz, 16L).toInt()); it.readFully(fmt)
                        rate = (fmt[4].toInt() and 0xFF) or ((fmt[5].toInt() and 0xFF) shl 8) or
                            ((fmt[6].toInt() and 0xFF) shl 16) or ((fmt[7].toInt() and 0xFF) shl 24)
                        ch = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                        val bits = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                        if (bits != 16) return null
                    }
                    "data" -> return Triple(it.filePointer, rate, ch)
                    else -> it.seek(it.filePointer + sz + (sz and 1))
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    /** 流式逐块读 data 区（16bit LE → short 单声道），回调带本块起始秒；避免整段载入内存 */
    private fun feedBlocks(wav: File, dataOff: Long, frames: Int, feed: (ShortArray, Int, Long) -> Unit) {
        RandomAccessFile(wav, "r").use { raf ->
            raf.seek(dataOff)
            val block = 3200
            val buf = ByteArray(block * 2)
            var left = frames
            var off = 0L
            while (left > 0) {
                val n = minOf(block, left)
                raf.readFully(buf, 0, n * 2)
                val shorts = ShortArray(n)
                for (j in 0 until n) {
                    shorts[j] = ((buf[j * 2].toInt() and 0xFF) or ((buf[j * 2 + 1].toInt()) shl 8)).toShort()
                }
                feed(shorts, n, off / TARGET_RATE)
                off += n
                left -= n
            }
        }
    }

    private fun write(path: String, content: String) {
        try { File(path).writeText(content) } catch (e: Exception) { Log.e(TAG, "写出失败", e) }
    }
}
