package com.classfox.droid

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.classfox.droid.databinding.FragmentSettingsBinding
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File

/** 设置页：识别引擎、分句模式、阈值、关键词编辑、数据目录 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.switchMock.isChecked = Prefs.isMockAsr(requireContext())
        b.switchMock.setOnCheckedChangeListener { _, checked ->
            Prefs.setMockAsr(requireContext(), checked)
            hint()
        }

        b.toggleSplit.check(
            if (Prefs.splitMode(requireContext()) == SentenceSplitter.MODE_SPLIT_FAST)
                R.id.btnFast else R.id.btnConservative
        )
        b.toggleSplit.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, _: Int, isChecked: Boolean ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode =
                if (b.toggleSplit.checkedButtonId == R.id.btnFast)
                    SentenceSplitter.MODE_SPLIT_FAST
                else SentenceSplitter.MODE_MERGE_CONSERVATIVE
            Prefs.setSplitMode(requireContext(), mode)
            (activity as? MainActivity)?.service?.setSplitMode(mode)
            hint()
        }

        val saved = Prefs.thresholdDb(requireContext())
        b.sliderThreshold.value = saved.toFloat()
        b.tvThreshold.text = "人声事件阈值：$saved dB"
        b.sliderThreshold.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val db = value.toInt()
            Prefs.setThresholdDb(requireContext(), db)
            b.tvThreshold.text = "人声事件阈值：$db dB"
            (activity as? MainActivity)?.service?.setVoiceThreshold(db.toDouble())
        }

        b.btnEditDanger.setOnClickListener { editKeywords(danger = true) }
        b.btnEditWarning.setOnClickListener { editKeywords(danger = false) }

        val dataDir = requireContext().getExternalFilesDir(null)
        b.tvDataDir.text = "转录/录音/词表都在应用私有目录：\n$dataDir"
        hint()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun hint() {
        b.tvEngineHint.text = if (Prefs.isMockAsr(requireContext()))
            "当前：模拟识别 —— 按内置课堂脚本产出文本，用于验证分句/转录/警报链路（模拟器或演示用）。下次开始监听时生效。"
        else
            "当前：端侧离线识别（sherpa-onnx 流式中文）—— 完全离线，无需网络。识别精度取决于模型，下次开始监听时生效。"
    }

    private fun editKeywords(danger: Boolean) {
        val f = if (danger) Keywords.dangerFile(requireContext())
        else Keywords.warningFile(requireContext())
        val et = EditText(requireContext()).apply {
            setPadding(48, 32, 48, 16)
            minLines = 10
            gravity = android.view.Gravity.TOP
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), R.color.cf_text))
            try { setText(f.readText()) } catch (_: Exception) { setText("") }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (danger) "编辑警报词（红色 · 危险）" else "编辑提醒词（黄色 · 提醒）")
            .setMessage("每行一个关键词，# 开头为注释。保存后立即生效。")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                Keywords.save(requireContext(), danger, et.text.toString())
                (activity as? MainActivity)?.service?.reloadKeywords()
                toastSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toastSaved() {
        android.widget.Toast.makeText(
            requireContext(), "已保存并重载词表", android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}
