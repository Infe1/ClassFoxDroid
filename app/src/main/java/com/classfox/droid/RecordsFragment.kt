package com.classfox.droid

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.classfox.droid.databinding.FragmentRecordsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 记录页：按课程分组的会话产物（转录 txt / 总结 md / 录音 wav），点转录看全文，点录音播放 */
class RecordsFragment : Fragment() {

    /** 与桌面版 generate_class_summary 相同的章节结构（对齐提示词） */
    private companion object {
        const val SUMMARY_SYSTEM_PROMPT = """你是一个专业的课堂笔记整理助手。请根据课堂录音转录和课程资料，生成一份结构化的 Markdown 课堂笔记。

笔记应包含以下章节：
# 📚 课堂笔记

## 📝 课程概要
（简要描述本节课的主题和内容）

## 🔑 核心知识点
（列出本节课的重要知识点，用编号列表）

## 💡 老师重点强调
（老师特别强调或反复提到的内容）

## 📋 作业与任务
（如果提到了作业、小测、DDL 等，列出来）

## ❓ 课堂问答
（记录课上的提问和回答）

## 📌 补充说明
（其他值得注意的信息）

请确保笔记内容准确、条理清晰。"""
    }

    private sealed class Row {
        /** level 0=课程 1=日期；collapsed 组不展开子行，点 header 切换 */
        class Header(val course: String, val day: String, val level: Int, val count: Int) : Row()
        class FileRow(val file: File, val isAudio: Boolean) : Row()
    }

    private var _b: FragmentRecordsBinding? = null
    private val b get() = _b!!
    private val rows = mutableListOf<Row>()
    /** 折叠状态：key = "课程|日期"（课程级 key = "课程|"），默认展开 */
    private val collapsed = mutableSetOf<String>()
    private var player: MediaPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentRecordsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = Adapter()
        b.btnRefresh.setOnClickListener { reload() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onPause() {
        stopPlayer()
        super.onPause()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    /** 从文件名提取日期（class_transcript_2026-09-20_* / classfox_20260920_* / summary_*_2026-09-20_*），无则 lastModified */
    private fun dayOf(f: File): String {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(f.name)
        if (m != null) return m.value
        val m2 = Regex("(\\d{4})(\\d{2})(\\d{2})_\\d{6}").find(f.name)
        if (m2 != null) return "${m2.groupValues[1]}-${m2.groupValues[2]}-${m2.groupValues[3]}"
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(f.lastModified()))
    }

    private fun reload() {
        rows.clear()
        val base = File(requireContext().getExternalFilesDir(null), "classfox")
        val groups = linkedMapOf<String, MutableList<File>>()
        if (base.exists()) {
            // 课程子目录（keywords 是词表目录，排除）；转录 txt / 总结 md / 录音 wav
            base.listFiles { f -> f.isDirectory && f.name != "keywords" }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { dir ->
                    val files = dir.listFiles { f -> f.isFile && f.extension in setOf("txt", "wav", "md") }
                    if (!files.isNullOrEmpty()) groups[dir.name] = files.toMutableList()
                }
            // 旧版本平铺在根目录的文件 → 未分类
            val loose = base.listFiles { f -> f.isFile && f.extension in setOf("txt", "wav", "md") }
            if (!loose.isNullOrEmpty()) groups["未分类（旧记录）"] = loose.toMutableList()
        }
        // 三级结构：课程 → 日期 → 文件；折叠状态在记忆集合里，reload 后保持
        for ((course, files) in groups) {
            files.sortByDescending { it.lastModified() }
            val byDay = linkedMapOf<String, MutableList<File>>()
            for (f in files) byDay.getOrPut(dayOf(f)) { mutableListOf() }.add(f)
            rows.add(Row.Header(course, "", 0, files.size))
            for ((day, dayFiles) in byDay) {
                val dayKey = "$course|$day"
                rows.add(Row.Header(course, day, 1, dayFiles.size))
                if (dayKey in collapsed) continue
                for (f in dayFiles) rows.add(Row.FileRow(f, f.extension == "wav"))
            }
        }
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.adapter?.notifyDataSetChanged()
    }

    /** 点 header：切换折叠并重建行；course 级折叠其下所有日期组 */
    private fun toggleHeader(pos: Int) {
        when (val row = rows[pos]) {
            is Row.Header -> {
                if (row.level == 1) {
                    val key = "${row.course}|${row.day}"
                    if (key in collapsed) collapsed.remove(key) else collapsed.add(key)
                } else {
                    val prefix = "${row.course}|"
                    val anyOpen = rows.any { it is Row.Header && it.level == 1 && it.course == row.course && "${it.course}|${it.day}" !in collapsed }
                    if (anyOpen) rows.filterIsInstance<Row.Header>().filter { it.level == 1 && it.course == row.course }
                        .forEach { collapsed.add("${it.course}|${it.day}") }
                    else rows.filterIsInstance<Row.Header>().filter { it.level == 1 && it.course == row.course }
                        .forEach { collapsed.remove("${it.course}|${it.day}") }
                }
                reload()
            }
            else -> {}
        }
    }

    private fun showTranscript(f: File) {
        val content = try { f.readText() } catch (e: Exception) { "读取失败: ${e.message}" }
        val tv = TextView(requireContext()).apply {
            setPadding(48, 32, 48, 32)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), R.color.cf_text))
            text = content
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(f.name)
            .setView(tv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("AI 总结") { _, _ -> aiSummarize(f) }
            .show()
    }

    /**
     * AI 课后总结（对齐桌面版 generate_class_summary 的提示词与章节结构）。
     * 结果存转录同目录 summary_<转录名>.md，记录页以「总结」类型展示。
     * 课程资料可选：classfox/current_class_material.txt 存在则一并喂给模型。
     */
    private fun aiSummarize(f: File) {
        val ctx = requireContext()
        val progress = TextView(ctx).apply {
            setPadding(48, 32, 48, 32)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.cf_text_dim))
            text = "正在生成课堂笔记…（长转录约需几十秒，请勿关闭）"
        }
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("AI 课堂笔记")
            .setView(progress)
            .setNegativeButton("取消", null)
            .show()
        Thread {
            try {
                val transcript = f.readText()
                val material = try {
                    File(ctx.getExternalFilesDir(null), "classfox/current_class_material.txt")
                        .readText().trim()
                } catch (_: Exception) { "" }
                val note = LlmClient.chat(
                    ctx, SUMMARY_SYSTEM_PROMPT,
                    "【课程资料（PPT内容）】\n${material.ifBlank { "暂无课程资料" }}\n\n【完整课堂转录】\n$transcript\n\n请生成课堂笔记。",
                    maxTokens = 8000, temperature = 0.5, timeoutMs = 180_000
                )
                if (note.isBlank()) throw IllegalStateException("模型返回了空内容（思考型模型可能截断，换个模型或缩短转录）")
                val out = File(f.parentFile, "summary_" + f.name.removeSuffix(".txt") + ".md")
                out.writeText(note)
                activity?.runOnUiThread {
                    if (_b == null) return@runOnUiThread
                    progress.text = note
                    progress.typeface = android.graphics.Typeface.MONOSPACE
                    progress.setTextColor(ContextCompat.getColor(ctx, R.color.cf_text))
                    progress.setTextIsSelectable(true)
                    reload()
                    Toast.makeText(ctx, "已保存：${out.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (_b == null) return@runOnUiThread
                    progress.text = "✗ 总结失败：${e.message}\n请检查设置 → AI 救场 的接口配置。"
                }
            }
        }.apply { name = "classfox-summary" }.start()
    }

    private fun playWav(f: File) {
        stopPlayer()
        player = MediaPlayer.create(requireContext(), android.net.Uri.fromFile(f))
        if (player == null) {
            Toast.makeText(requireContext(), "无法播放该录音", Toast.LENGTH_SHORT).show()
            return
        }
        player?.setOnCompletionListener { stopPlayer() }
        player?.start()
        Toast.makeText(requireContext(), "▶ 播放中（点列表其他项停止）", Toast.LENGTH_SHORT).show()
    }

    private fun stopPlayer() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(pos: Int): Int =
            if (rows[pos] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                HeaderVH(layoutInflater.inflate(R.layout.item_record_header, parent, false))
            } else {
                FileVH(layoutInflater.inflate(R.layout.item_record, parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (val row = rows[pos]) {
                is Row.Header -> {
                    h as HeaderVH
                    if (row.level == 0) {
                        h.title.text = "📚 ${row.course}"
                        h.count.text = "${row.count} 条"
                        h.arrow.text = "▾"
                        h.title.setTextColor(ContextCompat.getColor(requireContext(), R.color.cf_primary))
                    } else {
                        h.title.text = "　📅 ${row.day}"
                        h.count.text = "${row.count} 条"
                        h.arrow.text = if ("${row.course}|${row.day}" in collapsed) "▸" else "▾"
                        h.title.setTextColor(ContextCompat.getColor(requireContext(), R.color.cf_text))
                    }
                    h.row.setOnClickListener { toggleHeader(h.bindingAdapterPosition) }
                }
                is Row.FileRow -> {
                    h as FileVH
                    val f = row.file
                    h.title.text = f.name
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    h.sub.text = "${df.format(Date(f.lastModified()))} · ${f.length() / 1024} KB"
                    val (tag, color) = when (f.extension) {
                        "wav" -> "录音" to R.color.cf_ok
                        "md" -> "总结" to R.color.cf_warn
                        else -> "转录" to R.color.cf_primary
                    }
                    h.tag.text = tag
                    h.tag.setTextColor(ContextCompat.getColor(requireContext(), color))
                    h.row.setOnClickListener {
                        stopPlayer()
                        if (row.isAudio) playWav(f) else showTranscript(f)
                    }
                }
            }
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.headerTitle)
        val count: TextView = v.findViewById(R.id.headerCount)
        val arrow: TextView = v.findViewById(R.id.headerArrow)
        val row: View = v
    }

    private class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.itemTitle)
        val sub: TextView = v.findViewById(R.id.itemSub)
        val tag: TextView = v.findViewById(R.id.itemTag)
        val row: View = v
    }
}
