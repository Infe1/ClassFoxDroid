package com.classfox.droid

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.classfox.droid.databinding.FragmentSettingsBinding
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File

/** 设置页：识别引擎、分句模式、阈值、关键词编辑、数据目录 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val selfTestLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) runSelfTest(uri)
        }

    /** 离线识别引擎初始化诊断：真机上"没有字幕"时用它定位 */
    private fun runEngineDiag() {
        val tv = b.tvSelfTest
        tv.visibility = View.VISIBLE
        tv.text = "诊断中：解压并初始化离线识别模型（首次约几秒到十几秒）…"
        b.btnDiag.isEnabled = false
        val ctx = requireContext().applicationContext
        Thread {
            val engine = OfflineAsrEngine(ctx) { _, _ -> }
            val t0 = System.currentTimeMillis()
            val err = engine.init()
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            try { engine.release() } catch (_: Exception) {}
            activity?.runOnUiThread {
                if (_b == null) return@runOnUiThread
                b.btnDiag.isEnabled = true
                tv.text = if (err == null)
                    "✓ 引擎正常：初始化成功，耗时 ${"%.1f".format(secs)} 秒。实时监听应能出字幕；若仍无字幕，请确认监听时周围有说话声。"
                else
                    "✗ 引擎异常：$err\n请把这段文字发给开发者。"
            }
        }.apply { name = "classfox-diag" }.start()
    }

    private fun runSelfTest(uri: Uri) {
        val tv = b.tvSelfTest
        tv.visibility = View.VISIBLE
        tv.text = "准备中…"
        b.btnSelfTest.isEnabled = false
        val ctx = requireContext()
        AudioSelfTest.run(ctx, uri, object : AudioSelfTest.Callbacks {
            override fun onLog(msg: String) {
                activity?.runOnUiThread { if (_b != null) tv.append("\n$msg") }
            }

            override fun onDone(text: String?) {
                activity?.runOnUiThread {
                    if (_b == null) return@runOnUiThread
                    b.btnSelfTest.isEnabled = true
                    if (text == null) {
                        tv.append("\n✗ 未完成")
                    } else {
                        val dlgTv = EditText(ctx).apply {
                            setPadding(48, 32, 48, 32)
                            textSize = 14f
                            setTextColor(ContextCompat.getColor(ctx, R.color.cf_text))
                            setText(text)
                            setTextIsSelectable(true)
                        }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                            .setTitle("自检识别结果")
                            .setView(dlgTv)
                            .setPositiveButton("关闭", null)
                            .show()
                    }
                }
            }
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.switchPortrait.isChecked = Prefs.forcePortrait(requireContext())
        b.switchPortrait.setOnCheckedChangeListener { _, checked ->
            Prefs.setForcePortrait(requireContext(), checked)
            (activity as? MainActivity)?.applyOrientation()
        }

        b.toggleEngine.check(
            when (Prefs.asrMode(requireContext())) {
                Prefs.ASR_SYSTEM -> R.id.btnEngineSystem
                Prefs.ASR_OFFLINE -> R.id.btnEngineOffline
                else -> R.id.btnEngineMock
            }
        )
        b.toggleEngine.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (b.toggleEngine.checkedButtonId) {
                R.id.btnEngineSystem -> Prefs.ASR_SYSTEM
                R.id.btnEngineOffline -> Prefs.ASR_OFFLINE
                else -> Prefs.ASR_MOCK
            }
            Prefs.setAsrMode(requireContext(), mode)
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
        // 拖动结束才提示，避免滑动过程 Toast 刷屏
        b.sliderThreshold.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                toast("已保存：人声阈值")
            }
        })

        // 运行日志：保留天数 + 查看入口（日志本体已从监听页移除，见 LogStore）
        val logDays = LogStore.retentionDays(requireContext())
        b.sliderLogRetention.value = logDays.toFloat().coerceIn(1f, 30f)
        b.tvLogRetention.text = "日志保留：$logDays 天"
        b.sliderLogRetention.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val d = value.toInt()
            LogStore.setRetentionDays(requireContext(), d)
            b.tvLogRetention.text = "日志保留：$d 天"
        }
        b.sliderLogRetention.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                toast("已保存：日志保留天数")
            }
        })
        b.btnViewLog.setOnClickListener { showTodayLog() }

        b.toggleModel.check(
            if (Prefs.asrModel(requireContext()) == Prefs.MODEL_SMALL)
                R.id.btnModelSmall else R.id.btnModelLarge
        )
        b.toggleModel.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val m =
                if (b.toggleModel.checkedButtonId == R.id.btnModelSmall) Prefs.MODEL_SMALL
                else Prefs.MODEL_LARGE
            Prefs.setAsrModel(requireContext(), m)
            Toast.makeText(requireContext(), "已切换模型，下次开始监听时生效", Toast.LENGTH_SHORT).show()
        }

        // AI 救场配置：输入即保存
        b.etLlmBase.setText(Prefs.llmBase(requireContext()))
        b.etLlmKey.setText(Prefs.llmKey(requireContext()))
        b.etLlmModel.setText(Prefs.llmModel(requireContext()))
        val saveLlm = {
            Prefs.setLlm(
                requireContext(),
                b.etLlmBase.text.toString(), b.etLlmKey.text.toString(), b.etLlmModel.text.toString()
            )
        }
        b.etLlmBase.addTextChangedListener(saveWatcher(saveLlm))
        b.etLlmKey.addTextChangedListener(saveWatcher(saveLlm))
        b.etLlmModel.addTextChangedListener(saveWatcher(saveLlm))
        // 输入即存已生效；失焦时给出明确反馈（避免每个按键都弹 Toast）
        val llmSavedOnBlur = { v: View, hasFocus: Boolean ->
            if (!hasFocus) toast("已保存：AI 救场配置")
        }
        b.etLlmBase.setOnFocusChangeListener(llmSavedOnBlur)
        b.etLlmKey.setOnFocusChangeListener(llmSavedOnBlur)
        b.etLlmModel.setOnFocusChangeListener(llmSavedOnBlur)

        b.btnDiag.setOnClickListener { runEngineDiag() }
        b.btnSelfTest.setOnClickListener {
            selfTestLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
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

    private fun saveWatcher(save: () -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = save()
    }

    private fun hint() {
        b.tvEngineHint.text = when (Prefs.asrMode(requireContext())) {
            Prefs.ASR_SYSTEM ->
                "当前：系统识别（厂商接口）—— 调用设备自带的语音识别服务（通常为讯飞/厂商自研引擎），不占安装包体积；需设备提供该服务，若无将自动回退到离线识别。下次开始监听时生效。"
            Prefs.ASR_OFFLINE ->
                "当前：端侧离线识别（sherpa-onnx 流式中文）—— 完全离线、行为一致，模型已内置；识别精度取决于内置模型大小。下次开始监听时生效。"
            else ->
                "当前：模拟识别 —— 按内置课堂脚本产出文本，用于验证分句/转录/警报链路（模拟器或演示用）。下次开始监听时生效。"
        }
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
                toast("已保存并重载词表")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    /** 今日日志对话框：内存缓冲 + 当天落盘文件合并展示，monospace 可选中便于复制反馈 */
    private fun showTodayLog() {
        val ctx = requireContext()
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(
            ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs/log_$day.txt"
        )
        val disk = try { file.readText().trimEnd() } catch (_: Exception) { "" }
        val memText = LogStore.recent().joinToString("\n")
        val content = if (disk.isNotBlank()) disk else memText.ifBlank { "今日暂无日志" }

        val tv = android.widget.TextView(ctx).apply {
            setPadding(48, 32, 48, 32)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.cf_text))
            text = content
            setTextIsSelectable(true)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("今日日志（$day）")
            .setView(tv)
            .setPositiveButton("关闭", null)
            .show()
    }
}
