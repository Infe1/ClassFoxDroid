package com.classfox.droid

import android.content.Context
import java.io.File

/**
 * 关键词两级词表（对齐桌面版）：
 * 危险(danger) = 点名/抽查/签到类；提醒(warning) = 作业/考试/重点类。
 * 词表存外部私有目录 keywords 下的两个 .txt，每行一词，# 开头为注释，可编辑后热重载。
 */
object Keywords {
    const val DEFAULT_DANGER =
        "点名\n抽查\n叫人\n签到\n哪位同学\n谁来\n站起来\n请回答\n回答\n加分\n扫码\n签个到\nquestion"
    const val DEFAULT_WARNING =
        "重点\n作业\n截止日期\n组队\n考试\n注意\n小测\n汇报\ndeadline\nddl\n下节课\n测验"

    fun dangerFile(ctx: Context) = kwFile(ctx, true)
    fun warningFile(ctx: Context) = kwFile(ctx, false)

    private fun kwFile(ctx: Context, danger: Boolean): File {
        val dir = File(ctx.getExternalFilesDir(null), "keywords")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, if (danger) "danger_keywords.txt" else "warning_keywords.txt")
        if (!f.exists()) {
            try { f.writeText(if (danger) DEFAULT_DANGER else DEFAULT_WARNING) } catch (_: Exception) {}
        }
        return f
    }

    fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** 返回 (danger, warning) 两张词表；文件损坏时退回内置默认 */
    fun load(ctx: Context): Pair<List<String>, List<String>> {
        val d = try { parse(kwFile(ctx, true).readText()) } catch (_: Exception) { parse(DEFAULT_DANGER) }
        val w = try { parse(kwFile(ctx, false).readText()) } catch (_: Exception) { parse(DEFAULT_WARNING) }
        return d to w
    }

    fun save(ctx: Context, danger: Boolean, text: String) {
        try { kwFile(ctx, danger).writeText(text) } catch (_: Exception) {}
    }
}
