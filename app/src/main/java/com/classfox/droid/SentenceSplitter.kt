package com.classfox.droid

/**
 * 双模式分句器 —— 两种策略并存,可在构建/设置时选择,保留优化空间。
 *
 * MODE_SPLIT_FAST  (激进切分,默认):"能切就切,半句也行"
 *   - ASR 每次回调视为一次自然停顿
 *   - 句末标点(。！？!?；;……) → 立即定稿
 *   - 句中停顿标点(，,、：:)且累计≥8字 → 半句也定稿
 *   - 无标点但≥30字 → 强切
 *
 * MODE_MERGE_CONSERVATIVE (保守合并):对齐桌面版 monitor_service 逻辑
 *   - 仅在句末标点处定稿
 *   - 未闭合内容与下一段"能合就合"(≤3s 窗口由调用方节流体现;此处按
 *     ASR 节奏自然合并),不产生半句
 *
 * 两种模式共用同一转录出口,喂同一份 ASR 流,可随时切换对比效果。
 */
class SentenceSplitter(private val mode: Int = MODE_SPLIT_FAST) {

    companion object {
        const val MODE_SPLIT_FAST = 0
        const val MODE_MERGE_CONSERVATIVE = 1

        private val finalPunct = setOf('。', '！', '？', '!', '?', '；', ';', '…')
        private val midPunct = setOf('，', ',', '、', '：', ':')
        private const val minHalfLen = 8
        private const val maxLenForceCut = 30

        fun isMeaningful(text: String): Boolean {
            val t = text.trim()
            if (t.length < 2) return false
            val compact = t.replace(Regex("[\\s\\p{Punct}]+"), "")
            return compact.length >= 2 &&
                compact.any { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
        }

        fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
    }

    // 流式去重:只输出增量
    private var pending = ""
    private var consumed = 0
    private var lastObserved = ""

    @Volatile
    private var currentMode: Int = mode

    /** 运行中切换模式(切换即冲刷当前模式残留) */
    fun setMode(newMode: Int) {
        if (newMode == currentMode) return
        currentMode = newMode
        consumed = 0
        pending = ""
    }

    /** 最近定稿句(去重用,保守模式合并判断用) */
    private val recent = ArrayDeque<String>()

    /**
     * 喂一段新识别文本(partial 或 final 均可,自动 diff)。
     * 返回本次应定稿的句子列表。
     */
    fun feed(text: String): List<String> {
        val clean = normalize(text)
        if (clean.isEmpty()) return emptyList()

        // 识别器回退(新文本是旧文本前缀)
        if (pending.isNotEmpty() && clean.length < pending.length && pending.startsWith(clean)) {
            pending = clean
            consumed = 0
            return emptyList()
        }

        // 旧文本延伸 → 合并
        if (pending.isNotEmpty() && clean.startsWith(pending)) {
            pending = clean
        } else if (pending.isNotEmpty()) {
            // 完全新内容:先处理旧残留
            val tail = when (currentMode) {
                MODE_SPLIT_FAST -> flushRemaining()
                else -> emptyList() // 保守模式:丢弃未闭合残留,与桌面"只留完整句"一致
            }
            pending = clean
            consumed = 0
            return tail
        } else {
            pending = clean
        }

        return when (currentMode) {
            MODE_SPLIT_FAST -> splitFast()
            else -> mergeConservative()
        }
    }

    /** 停止/切换时强制输出残留(仅激进模式有意义) */
    fun flushRemaining(): List<String> {
        val rest = pending.trim()
        pending = ""
        consumed = 0
        return if (rest.length >= 2 && isMeaningful(rest)) listOf(rest) else emptyList()
    }

    /** 激进:能切就切 */
    private fun splitFast(): List<String> {
        val out = mutableListOf<String>()
        var start = consumed.coerceAtMost(pending.length)
        var i = start
        val chars = pending

        while (i < chars.length) {
            val c = chars[i]
            val segLen = i - start + 1

            // 无标点超长 → 强切
            if (segLen >= maxLenForceCut) {
                addSentence(out, chars.substring(start, i + 1))
                start = i + 1
                i = start
                continue
            }
            if (c in finalPunct) {
                addSentence(out, chars.substring(start, i + 1))
                start = i + 1
                i = start
                continue
            }
            if (c in midPunct && segLen >= minHalfLen) {
                addSentence(out, chars.substring(start, i + 1))
                start = i + 1
                i = start
                continue
            }
            i++
        }

        consumed = start
        pending = if (start < chars.length) chars.substring(start) else ""
        if (pending.isEmpty()) consumed = 0
        return out
    }

    /** 保守:能合就合,闭合才定稿(对齐桌面版) */
    private fun mergeConservative(): List<String> {
        val out = mutableListOf<String>()
        // 找最后一个句末标点,把其后所有内容合并为一句,标点前各自定稿
        var searchFrom = consumed.coerceAtMost(pending.length)
        var lastCut = -1
        for (i in searchFrom until pending.length) {
            if (pending[i] in finalPunct) lastCut = i
        }
        if (lastCut >= 0) {
            val whole = pending.substring(0, lastCut + 1)
            // 按句末标点切成多句
            val pieces = whole.split(Regex("(?<=[。！？!?；;…])"))
            for (p in pieces) {
                val s = p.trim()
                if (s.isNotEmpty() && isMeaningful(s)) addSentence(out, s)
            }
            pending = pending.substring(lastCut + 1)
            consumed = 0
        }
        return out
    }

    private fun addSentence(out: MutableList<String>, s: String) {
        val t = s.trim()
        if (t.isEmpty() || !isMeaningful(t)) return
        // 与最近定稿句完全重复则丢弃
        if (recent.any { it == t }) return
        recent.addLast(t)
        if (recent.size > 8) recent.removeFirst()
        out.add(t)
    }
}
