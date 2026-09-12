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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 记录页：会话产物列表（转录 txt / 录音 wav），点转录可查看全文，点录音可播放 */
class RecordsFragment : Fragment() {

    data class SessionFile(
        val file: File,
        val isTranscript: Boolean
    )

    private var _b: FragmentRecordsBinding? = null
    private val b get() = _b!!
    private val items = mutableListOf<SessionFile>()
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
        items.clear()
        val dir = File(requireContext().getExternalFilesDir(null), "classfox")
        if (dir.exists()) {
            items.addAll(
                dir.listFiles { f -> f.isFile }
                    ?.filter { it.extension == "txt" || it.extension == "wav" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { SessionFile(it, it.extension == "txt") }
                    ?: emptyList()
            )
        }
        b.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
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

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_record, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            val f = item.file
            h.title.text = f.name
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val sizeKb = f.length() / 1024
            h.sub.text = "${df.format(Date(f.lastModified()))} · $sizeKb KB"
            h.tag.text = if (item.isTranscript) "转录" else "录音"
            h.tag.setTextColor(
                ContextCompat.getColor(
                    requireContext(), if (item.isTranscript) R.color.cf_primary else R.color.cf_ok
                )
            )
            h.row.setOnClickListener {
                stopPlayer()
                if (item.isTranscript) showTranscript(f) else playWav(f)
            }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.itemTitle)
        val sub: TextView = v.findViewById(R.id.itemSub)
        val tag: TextView = v.findViewById(R.id.itemTag)
        val row: View = v
    }
}
