package com.classfox.droid

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：内存环形缓冲 + 按天落盘（logs/log_yyyyMMdd.txt）。
 * 监听页不再直接展示日志；设置页提供查看入口与保留天数配置。
 * 单例，所有写入自动切天、自动清理过期文件。
 */
object LogStore {

    private const val DIR = "logs"
    private const val MAX_MEMORY_LINES = 500
    private const val KEY_RETENTION = "log_retention_days"

    private val mem = ArrayDeque<String>()
    private var lastDay = ""

    /** 保留天数：3/7/30，默认 7 */
    fun retentionDays(ctx: Context): Int =
        Prefs.get(ctx).getInt(KEY_RETENTION, 7)

    fun setRetentionDays(ctx: Context, days: Int) {
        Prefs.get(ctx).edit().putInt(KEY_RETENTION, days.coerceIn(1, 90)).apply()
        cleanup(ctx)
    }

    /** 追加一行（时间戳前缀），同步写当天文件 */
    @Synchronized
    fun append(ctx: Context, line: String) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val full = "$ts  $line"
        mem.addLast(full)
        while (mem.size > MAX_MEMORY_LINES) mem.removeFirst()
        try {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            if (day != lastDay) {
                lastDay = day
                cleanup(ctx)
            }
            File(dir, "log_$day.txt").appendText("$full\n")
        } catch (_: Exception) {}
    }

    /** 内存缓冲快照（最近 500 行） */
    fun recent(): List<String> = synchronized(mem) { mem.toList() }

    /** 当天及往期日志文件列表（旧→新） */
    fun files(ctx: Context): List<File> = try {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, DIR)
        dir.listFiles { f -> f.isFile && f.name.startsWith("log_") }
            ?.sortedBy { it.name } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** 删除超过保留天数的日志文件 */
    fun cleanup(ctx: Context) {
        try {
            val keep = retentionDays(ctx)
            val cutoff = System.currentTimeMillis() - keep * 86_400_000L
            files(ctx).forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {}
    }

    /**
     * 今日日志对话框（v0.10.2 起为共享入口：设置页「查看今日日志」+ 监听页「日志」按钮）。
     * 内存缓冲 + 当天落盘文件合并展示，monospace 可选中便于复制反馈。
     */
    fun showTodayDialog(activity: android.app.Activity) {
        val ctx = activity.applicationContext
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(
            ctx.getExternalFilesDir(null) ?: ctx.filesDir, "$DIR/log_$day.txt"
        )
        val disk = try { file.readText().trimEnd() } catch (_: Exception) { "" }
        val memText = recent().joinToString("\n")
        val content = if (disk.isNotBlank()) disk else memText.ifBlank { "今日暂无日志" }

        val tv = android.widget.TextView(activity).apply {
            setPadding(48, 32, 48, 32)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("今日日志（$day）")
            .setView(tv)
            .setPositiveButton("关闭", null)
            .show()
        tv.text = content
    }
}
