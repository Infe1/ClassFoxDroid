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

/** 记录页：按课程分组的会话产物（转录 txt / 录音 wav），点转录看全文，点录音播放 */
class RecordsFragment : Fragment() {

    private sealed class Row {
        class Header(val course: String, val count: Int) : Row()
        class FileRow(val file: File, val isTranscript: Boolean) : Row()
    }

    private var _b: FragmentRecordsBinding? = null
    private val b get() = _b!!
    private val rows = mutableListOf<Row>()
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

    private fun reload() {
        rows.clear()
        val base = File(requireContext().getExternalFilesDir(null), "classfox")
        val groups = linkedMapOf<String, MutableList<File>>()
        if (base.exists()) {
            // 课程子目录（keywords 是词表目录，排除）
            base.listFiles { f -> f.isDirectory && f.name != "keywords" }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { dir ->
                    val files = dir.listFiles { f -> f.isFile && (f.extension == "txt" || f.extension == "wav") }
                    if (!files.isNullOrEmpty()) groups[dir.name] = files.toMutableList()
                }
            // 旧版本平铺在根目录的文件 → 未分类
            val loose = base.listFiles { f -> f.isFile && (f.extension == "txt" || f.extension == "wav") }
            if (!loose.isNullOrEmpty()) groups["未分类（旧记录）"] = loose.toMutableList()
        }
        for ((course, files) in groups) {
            files.sortByDescending { it.lastModified() }
            rows.add(Row.Header(course, files.size))
            for (f in files) rows.add(Row.FileRow(f, f.extension == "txt"))
        }
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.adapter?.notifyDataSetChanged()
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
            .show()
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
                    h.title.text = "📚 ${row.course}"
                    h.count.text = "${row.count} 条"
                }
                is Row.FileRow -> {
                    h as FileVH
                    val f = row.file
                    h.title.text = f.name
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    h.sub.text = "${df.format(Date(f.lastModified()))} · ${f.length() / 1024} KB"
                    h.tag.text = if (row.isTranscript) "转录" else "录音"
                    h.tag.setTextColor(
                        ContextCompat.getColor(
                            requireContext(), if (row.isTranscript) R.color.cf_primary else R.color.cf_ok
                        )
                    )
                    h.row.setOnClickListener {
                        stopPlayer()
                        if (row.isTranscript) showTranscript(f) else playWav(f)
                    }
                }
            }
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.headerTitle)
        val count: TextView = v.findViewById(R.id.headerCount)
    }

    private class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.itemTitle)
        val sub: TextView = v.findViewById(R.id.itemSub)
        val tag: TextView = v.findViewById(R.id.itemTag)
        val row: View = v
    }
}
