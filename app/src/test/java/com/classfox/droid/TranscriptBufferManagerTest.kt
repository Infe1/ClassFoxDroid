package com.classfox.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TranscriptBufferManager 行为锁（v0.11.0 Argon-S3）：近重复判定 / 最近行窗口 /
 * 半句修正与精修替换的 id 定位，这些时序边界在模拟器上无法触发，靠单测锁定。
 */
class TranscriptBufferManagerTest {

    private lateinit var mgr: TranscriptBufferManager

    @Before
    fun setUp() {
        mgr = TranscriptBufferManager()
    }

    // ---------- pushRecent / recentSince ----------

    @Test
    fun `pushRecent 分配自增 id 且 recentSince 按时间过滤`() {
        val id1 = mgr.pushRecent("[10:00:00] 甲", at = 1_000L)
        val id2 = mgr.pushRecent("[10:00:01] 乙", at = 2_000L)
        assertEquals(1L, id1)
        assertEquals(2L, id2)
        assertTrue(mgr.recentSince(1_500L).endsWith("乙"))
        assertFalse(mgr.recentSince(1_500L).contains("甲"))
        assertEquals("[10:00:00] 甲\n[10:00:01] 乙", mgr.recentSince(0L))
    }

    @Test
    fun `窗口容量裁剪最旧行`() {
        val small = TranscriptBufferManager(recentCapacity = 3)
        repeat(5) { i -> small.pushRecent("[$i] 行$i", at = i.toLong()) }
        assertEquals(3, small.recentSize())
        val all = small.recentSince(0L)
        assertFalse(all.contains("行0"))
        assertFalse(all.contains("行1"))
        assertTrue(all.contains("行2"))
    }

    // ---------- 近重复判定 ----------

    @Test
    fun `完全相同判为近重复`() {
        assertFalse(mgr.isNearDuplicate("今天讲第三章函数的单调性。"))
        assertTrue(mgr.isNearDuplicate("今天讲第三章函数的单调性。"))
    }

    @Test
    fun `包含且长度比达标判为近重复`() {
        assertFalse(mgr.isNearDuplicate("重点来了"))
        // 包含且 4*10 >= 5*8（短/长 ≤ 0.8）→ 近重复；长句超过 1.25 倍则不命中
        assertTrue(mgr.isNearDuplicate("重点来了吗"))
        assertFalse(mgr.isNearDuplicate("重点来了，这个判定方法期末必考。"))
    }

    @Test
    fun `高相似度判为近重复`() {
        assertFalse(mgr.isNearDuplicate("这是一个非常普通的测试句子内容"))
        // 与前句仅个别字不同，LCS 占比 ≥0.88
        assertTrue(mgr.isNearDuplicate("这是一个非常普通的测试句子内容啊"))
    }

    @Test
    fun `不同句子不误判`() {
        assertFalse(mgr.isNearDuplicate("第一句话完全不同"))
        assertFalse(mgr.isNearDuplicate("第二句话风马牛不相及"))
        assertFalse(mgr.isNearDuplicate("第三句话又说了别的什么事情"))
    }

    @Test
    fun `滑窗容量 12 之后旧句不再判重`() {
        val base = "窗口淘汰测试句子"
        assertFalse(mgr.isNearDuplicate(base))
        // 12 条彼此措辞悬殊的填充句，确保都能入窗并把 base 挤出滑窗
        val fills = listOf(
            "苹果香蕉橘子橙子葡萄", "太阳月亮星星银河宇宙", "高铁飞机轮船汽车单车",
            "山川湖泊草原沙漠森林", "钢琴吉他小提琴架子鼓", "白菜萝卜番茄黄瓜土豆",
            "红色蓝色绿色黄色紫色", "数学物理化学地理历史", "足球篮球网球排球乒乓",
            "长江黄河泰山黄山昆仑", "春雨夏雷秋风冬雪霜降", "钢铁青铜黄金白银玉石"
        )
        for (f in fills) assertFalse("填充句自身不应判重: $f", mgr.isNearDuplicate(f))
        assertFalse(mgr.isNearDuplicate(base)) // 已被挤出滑窗
    }

    // ---------- 提交状态与替换 ----------

    @Test
    fun `recordCommitted 记录新行与行 id`() {
        val id = mgr.pushRecent("[10:00:00] 第一句。")
        mgr.recordCommitted("第一句。", closed = true, lineId = id)
        assertEquals("第一句。", mgr.lastCommitted)
        assertTrue(mgr.lastCommittedWasClosed)
        assertEquals(id, mgr.lastCommittedLineId)
    }

    @Test
    fun `按 id 精确替换保留时间戳前缀且行 id 不变`() {
        val id1 = mgr.pushRecent("[10:00:00] 第一句。", at = 1L)
        val id2 = mgr.pushRecent("[10:00:05] 这个判定方法", at = 2L)
        mgr.recordCommitted("这个判定方法", closed = false, lineId = id2)

        // 半句修正：同一行 id，文本延长
        mgr.replaceRecentText(id2, "这个判定方法", "这个判定方法期末必考，记到笔记本上。")
        mgr.updateLastCommitted("这个判定方法期末必考，记到笔记本上。", closed = false)
        assertEquals(id2, mgr.lastCommittedLineId)
        assertTrue(mgr.recentSince(0L).endsWith("[10:00:05] 这个判定方法期末必考，记到笔记本上。"))
        // 第一行不受影响
        assertTrue(mgr.recentSince(0L).contains("[10:00:00] 第一句。"))

        // 精修替换：按 id 改写，时间戳仍保留
        mgr.replaceRecentText(id2, "这个判定方法期末必考，记到笔记本上。", "这个判定方法期末必考。")
        mgr.updateLastCommitted("这个判定方法期末必考。", closed = true)
        assertTrue(mgr.recentSince(0L).endsWith("[10:00:05] 这个判定方法期末必考。"))
        assertFalse(mgr.replaceRecentText(id1, "不存在的旧文本", "新文本")) // 文本不匹配 → false
        assertFalse(mgr.replaceRecentText(999L, "任意", "任意")) // id 不存在 → false
    }

    @Test
    fun `replaceRecentSuffix 兜底命中最后一条匹配行`() {
        mgr.pushRecent("[10:00:00] 重复出现的话。", at = 1L)
        mgr.pushRecent("[10:00:01] 中间句。", at = 2L)
        mgr.pushRecent("[10:00:02] 重复出现的话。", at = 3L)
        assertTrue(mgr.replaceRecentSuffix("重复出现的话。", "重复出现的话（修正）。"))
        val all = mgr.recentSince(0L)
        assertEquals(1, Regex("（修正）").findAll(all).count()) // 只改最后一次出现
        assertTrue(all.contains("[10:00:00] 重复出现的话。")) // 第一次出现原样保留
    }

    @Test
    fun `clearAll 重置全部状态`() {
        mgr.pushRecent("[10:00:00] x", at = 1L)
        mgr.recordCommitted("x", true, mgr.pushRecent("[10:00:00] x", at = 2L))
        mgr.isNearDuplicate("某句")
        mgr.clearAll()
        assertEquals(0, mgr.recentSize())
        assertEquals(null, mgr.lastCommitted)
        assertEquals(-1L, mgr.lastCommittedLineId)
        assertFalse(mgr.isNearDuplicate("某句")) // 近重复窗也清了 → 再判不重复
    }
}
