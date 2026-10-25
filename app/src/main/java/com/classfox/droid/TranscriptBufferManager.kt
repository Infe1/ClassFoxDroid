package com.classfox.droid

/**
 * 转录状态管理（纯 Kotlin，可 JUnit；v0.11.0 由 RecorderService 抽取）。
 * 收敛此前散落在服务里的三块内存状态：recentLines（AI 快答数据源，带自增行 id）、
 * recentNorm（近重复判定滑窗）、lastCommitted（最近提交句及其行 id）。
 *
 * 设计要点：
 * - 行 id 自增唯一：精修替换按 id 精确改写，不再依赖脆弱的字符串后缀匹配（Argon S1）；
 *   replaceRecentSuffix 保留为滑窗溢出等找不到 id 场景的兜底。
 * - 所有方法 synchronized：历史上只在主线程访问（零成本），也允许未来跨线程调用；
 *   近重复滑窗与最近行窗口因此不存在数据竞争。
 * - 磁盘 I/O 不在本类（见 RecorderService 的 transcriptExecutor）。
 *
 * 行文本格式与桌面版对齐："[HH:mm:ss] 正文"——replace* 保留时间戳前缀与入队时刻。
 */
class TranscriptBufferManager(
    private val recentCapacity: Int = 600,
    private val normCapacity: Int = 12
) {
    /** 最近转录行：id 自增唯一；at 为入队墙钟毫秒（recentSince 按 it 过滤） */
    data class RecentLine(val id: Long, val at: Long, val line: String)

    private val recent = ArrayDeque<RecentLine>()
    private val norm = ArrayDeque<String>()
    private var nextId = 1L

    /** 最近一次提交的句文本（半句修正/精修替换的锚点） */
    var lastCommitted: String? = null
        private set

    /** 最近提交句是否以句末标点闭合 */
    var lastCommittedWasClosed = false
        private set

    /** 最近提交句对应的 recent 行 id（精修替换按此定位） */
    var lastCommittedLineId = -1L
        private set

    /** 追加一条最近转录行，返回其行 id */
    fun pushRecent(line: String, at: Long = System.currentTimeMillis()): Long = synchronized(this) {
        val id = nextId++
        recent.addLast(RecentLine(id, at, line))
        while (recent.size > recentCapacity) recent.removeFirst()
        id
    }

    /** 记录一次新句提交（新行） */
    fun recordCommitted(text: String, closed: Boolean, lineId: Long) = synchronized(this) {
        lastCommitted = text
        lastCommittedWasClosed = closed
        lastCommittedLineId = lineId
    }

    /** 最近提交句被原地改写（半句修正/精修替换）：文本与闭合位更新，行 id 不变 */
    fun updateLastCommitted(text: String, closed: Boolean) = synchronized(this) {
        lastCommitted = text
        lastCommittedWasClosed = closed
    }

    /** 按行 id 精确改写行文本（保留时间戳前缀与入队时刻）；id 不存在或文本不匹配返回 false */
    fun replaceRecentText(id: Long, oldText: String, newText: String): Boolean = synchronized(this) {
        val idx = recent.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = recent[idx]
        if (!old.line.endsWith(oldText)) return false
        recent[idx] = RecentLine(old.id, old.at, old.line.dropLast(oldText.length) + newText)
        true
    }

    /** 兜底：把 recent 中以 oldText 结尾的最新条目改写为 newText（如行已被滑窗挤出则 id 找不到） */
    fun replaceRecentSuffix(oldText: String, newText: String): Boolean = synchronized(this) {
        val idx = recent.indexOfLast { it.line.endsWith(oldText) }
        if (idx < 0) return false
        val old = recent[idx]
        recent[idx] = RecentLine(old.id, old.at, old.line.dropLast(oldText.length) + newText)
        true
    }

    /** 桌面版对齐的近重复判定：完全相等 / 包含且长度比≥0.8 / 相似度≥0.88；未命中时入窗 */
    fun isNearDuplicate(s: String): Boolean = synchronized(this) {
        val t = s.trim().trimEnd(*"。，！？!?；;、".toCharArray())
        for (r in norm) {
            if (r == t) return true
            val longer = maxOf(r.length, t.length)
            val shorter = minOf(r.length, t.length)
            if (longer >= 4 && shorter > 0 && shorter * 10 >= longer * 8 &&
                (r.contains(t) || t.contains(r))
            ) return true
            if (longer >= 6 && lcsRatio(r, t) >= 0.88) return true
        }
        norm.addLast(t)
        while (norm.size > normCapacity) norm.removeFirst()
        false
    }

    /** 取 cutoff 之后（墙钟毫秒）的最近转录行，按入队顺序拼接 */
    fun recentSince(cutoffMs: Long): String = synchronized(this) {
        recent.filter { it.at >= cutoffMs }.joinToString("\n") { it.line }
    }

    fun clearRecent() = synchronized(this) { recent.clear() }

    fun clearNorm() = synchronized(this) { norm.clear() }

    fun clearAll() {
        clearRecent()
        clearNorm()
        synchronized(this) {
            lastCommitted = null
            lastCommittedWasClosed = false
            lastCommittedLineId = -1L
        }
    }

    fun recentSize(): Int = synchronized(this) { recent.size }

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
}
