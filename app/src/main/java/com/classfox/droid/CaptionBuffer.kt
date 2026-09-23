package com.classfox.droid

/**
 * 进程级字幕缓冲：字幕历史与当前 partial 不再存于 Fragment 成员。
 * tab 切换由 MainActivity show/hide 保活；Activity 被系统重建（进程存活）时，
 * 新 Fragment 从这里恢复渲染，字幕不丢。进程被杀则服务也已停，属正常清零。
 */
object CaptionBuffer {
    private const val MAX_CHARS = 4000

    val history = StringBuilder()
    var partial: String = ""

    /** 超长裁掉最老一行，UI 与落盘格式一致（每行以 \n 结尾） */
    fun trim() {
        if (history.length > MAX_CHARS) {
            // 无换行（整屏一条超长行）时按半容量截断；旧写法 delete(0,1) 只删 1 个字符，每句都白跑一次 O(n)
            val idx = history.indexOf('\n')
            history.delete(0, if (idx >= 0) idx + 1 else MAX_CHARS / 2)
        }
    }

    /** 用 [text] 替换历史中最后一条等于 [orig] 的行；找不到返回 false（绝不追加，防双显） */
    fun replaceLine(orig: String, text: String): Boolean {
        val h = history
        val needle = "$orig\n"
        val idx = h.lastIndexOf(needle)
        if (idx < 0) return false
        h.replace(idx, idx + needle.length, "$text\n")
        return true
    }

    fun appendSentence(text: String) {
        history.append(text).append('\n')
        trim()
    }

    fun clear() {
        history.setLength(0)
        partial = ""
    }
}
