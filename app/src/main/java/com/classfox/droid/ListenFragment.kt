package com.classfox.droid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.classfox.droid.databinding.FragmentListenBinding

/**
 * 监听页：状态卡 + 课程名 + 控制按钮 + AI 入口 + 可回滚实时字幕。
 * 阈值/日志已移至设置页（问题3/4：避免误触、保持页面聚焦）。
 */
class ListenFragment : Fragment() {

    companion object {
        const val MODE_RESCUE = 0
        const val MODE_CATCHUP = 1
    }

    private var _b: FragmentListenBinding? = null
    private val b get() = _b!!
    private var isRunning = false
    private var isPaused = false

    // 手动回滚：字幕停在底部才自动跟随；历史/partial 存进程级 CaptionBuffer（抗重建）
    private var captionAtBottom = true

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                LogStore.append(requireContext(), "[OK] 麦克风权限已授予")
                startListening()
            } else {
                LogStore.append(requireContext(), "[X] 未授予麦克风权限")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentListenBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 手动回滚支持：用户上滑离开底部时，暂停自动跟随
        b.captionScroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
            captionAtBottom = isNearBottom(b.captionScroll)
        }

        b.btnQuickLog.setOnClickListener {
            LogStore.showTodayDialog(requireActivity())
        }

        b.btnStart.setOnClickListener { startListening() }
        b.btnPause.setOnClickListener { togglePause() }
        b.btnStop.setOnClickListener { stopListening() }
        b.btnAiRescue.setOnClickListener { openAiPanel(MODE_RESCUE) }
        b.btnAiCatchup.setOnClickListener { openAiPanel(MODE_CATCHUP) }
        b.btnAlertRescue.setOnClickListener { openAiPanel(MODE_RESCUE) }
        b.btnAlertCatchup.setOnClickListener { openAiPanel(MODE_CATCHUP) }

        refreshCourseHistory()
    }

    override fun onResume() {
        super.onResume()
        val act = activity as MainActivity
        act.listenSink = sink
        act.stateSink = stateSink
        syncFromService()
        render()
        refreshCourseHistory()
    }

    override fun onPause() {
        (activity as? MainActivity)?.let { it.listenSink = null; it.stateSink = null }
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // show/hide 保活模式下切回本页不走 onResume：在此刷新课程历史与字幕
        if (!hidden && _b != null) {
            refreshCourseHistory()
            renderCaption()
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun syncFromService() {
        val s = (activity as? MainActivity)?.service ?: return
        isRunning = s.isActive()
        isPaused = s.isPaused()
    }

    private val sink: (String, String, String?) -> Unit = { kind, text, orig ->
        when (kind) {
            "stopped" -> { isRunning = false; isPaused = false; render(); LogStore.append(requireContext(), "录音服务已停止") }
            "paused" -> { isPaused = true; render(); LogStore.append(requireContext(), text) }
            "resumed" -> { isPaused = false; render(); LogStore.append(requireContext(), text) }
            "danger_alert" -> {
                showAlert(text, danger = true)
                LogStore.append(requireContext(), "[危险] $text")
            }
            "warning_alert" -> {
                showAlert(text, danger = false)
                LogStore.append(requireContext(), "[提醒] $text")
            }
            // CaptionBuffer 的写入已上移到 MainActivity.eventReceiver（后台也不断流），这里只渲染与记日志
            "sentence" -> {
                renderCaption()
                LogStore.append(requireContext(), "[句] $text")
            }
            "sentence_corrected" -> {
                renderCaption()
                LogStore.append(requireContext(), "[精修] $text")
            }
            "voice" -> LogStore.append(requireContext(), text)
            "error" -> {
                // 启动失败（权限/音频设备/前台服务异常）：服务此时未进运行态也不发 stopped，必须在此复位 UI
                isRunning = false; isPaused = false
                render()
                LogStore.append(requireContext(), text)
            }
            "asr_status" -> {
                LogStore.append(requireContext(), text)
                // 引擎故障显性化：不再只留在一闪而过的日志里
                if (text.contains("失败") || text.contains("不可用")) showAlert(text, danger = true)
            }
            "partial" -> renderCaption()
        }
    }

    private val stateSink: (Double, Int, String?) -> Unit = { rms, secs, file ->
        if (_b != null) {
            val pct = ((rms + 90) / 90.0 * 100).toFloat().coerceIn(0f, 100f)
            b.volumeBar.progress = pct.toInt()
            b.tvDb.text = String.format("%.0f dB", maxOf(rms, -90.0))
            b.tvTime.text = "已监听 %02d:%02d".format(secs / 60, secs % 60)
            if (file != null) b.tvFile.text = file.substringAfterLast('/')
            val color = when {
                rms > -30 -> Color.rgb(229, 57, 53)
                rms > -45 -> Color.rgb(251, 140, 0)
                else -> Color.rgb(74, 222, 128)
            }
            b.volumeBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }

    private fun refreshCourseHistory() {
        if (_b == null) return
        val names = mutableSetOf<String>()
        names.addAll(Prefs.courseHistory(requireContext()))
        try {
            val base = java.io.File(requireContext().getExternalFilesDir(null), "classfox")
            base.listFiles { f -> f.isDirectory && f.name != "keywords" && f.name != "logs" }
                ?.forEach { names.add(it.name) }
        } catch (_: Exception) {}
        if (names.isEmpty()) return
        val adapter = android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1, names.sorted()
        )
        b.etCourse.setAdapter(adapter)
    }

    private fun startListening() {
        val act = activity as MainActivity ?: return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(
                if (Build.VERSION.SDK_INT >= 33)
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO)
            )
            return
        }
        if (isRunning) return
        val course = b.etCourse.text?.toString().orEmpty().trim()
        Prefs.addCourseHistory(requireContext(), course)
        val i = Intent(act, RecorderService::class.java)
        i.putExtra(RecorderService.EXTRA_COURSE, course)
        ContextCompat.startForegroundService(act, i)
        isRunning = true
        isPaused = false
        render()
        LogStore.append(requireContext(), "▶ 启动课堂监听…")
        CaptionBuffer.clear()
        renderCaption()
    }

    private fun stopListening() {
        if (!isRunning) return
        val act = activity as MainActivity ?: return
        act.startService(
            Intent(act, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
        )
        isRunning = false
        isPaused = false
        render()
        b.tvDb.text = "-90 dB"
        b.volumeBar.progress = 0
        hideAlert()
        LogStore.append(requireContext(), "■ 停止监听")
    }

    private fun togglePause() {
        if (!isRunning) return
        val act = activity as MainActivity ?: return
        val action = if (isPaused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
        act.startService(Intent(act, RecorderService::class.java).setAction(action))
    }

    private fun render() {
        val svc = (activity as? MainActivity)?.service
        b.btnStart.isEnabled = !isRunning
        b.btnPause.isEnabled = isRunning
        b.btnStop.isEnabled = isRunning
        b.btnStart.alpha = if (isRunning) 0.35f else 1f
        b.btnPause.alpha = if (isRunning) 1f else 0.35f
        b.btnStop.alpha = if (isRunning) 1f else 0.35f
        b.btnPause.text = if (isPaused) "继续" else "暂停"
        b.etCourse.isEnabled = !isRunning

        val (label, color) = when {
            !isRunning -> "● 未监听" to ContextCompat.getColor(requireContext(), R.color.cf_text_dim)
            isPaused -> "● 已暂停" to ContextCompat.getColor(requireContext(), R.color.cf_warn)
            else -> "● 监听中" to ContextCompat.getColor(requireContext(), R.color.cf_ok)
        }
        b.statusPill.text = label
        b.statusPill.background?.mutate()?.setTint(color)

        if (svc != null && isRunning) {
            b.tvFile.text = svc.currentTranscriptFile()?.name ?: b.tvFile.text
        }
    }

    private fun showAlert(text: String, danger: Boolean) {
        if (_b == null) return
        b.cardAlert.visibility = View.VISIBLE
        b.tvAlert.text = text
        val c = ContextCompat.getColor(
            requireContext(), if (danger) R.color.cf_danger else R.color.cf_warn
        )
        b.cardAlert.strokeColor = c
        b.tvAlertTag.text = if (danger) "危险 · 需要警惕" else "提醒"
        b.tvAlertTag.setTextColor(c)
        b.btnAlertDismiss.setOnClickListener { hideAlert() }
        b.rowAlertActions.visibility = View.VISIBLE
    }

    private fun hideAlert() {
        if (_b == null) return
        b.cardAlert.visibility = View.GONE
        b.rowAlertActions.visibility = View.GONE
    }

    // ---------- AI 救场 / 讲到哪了 ----------
    private var aiMode = MODE_RESCUE
    private var lastRescue: AiAssistant.RescueResult? = null
    private var lastSummary: String? = null
    private var aiHistory = mutableListOf<LlmClient.ChatMsg>()
    private var aiSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var aiBody: TextView? = null
    private var aiStatus: TextView? = null

    private fun openAiPanel(mode: Int) {
        if (!Prefs.llmReady(requireContext())) {
            android.widget.Toast.makeText(
                requireContext(), "请先在 设置 → AI 救场 配置大模型接口", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        aiMode = mode
        aiHistory = mutableListOf()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.ai_panel, null)
        sheet.setContentView(v)
        aiSheet = sheet
        aiBody = v.findViewById(R.id.tvAiBody)
        aiStatus = v.findViewById(R.id.tvAiStatus)
        v.findViewById<TextView>(R.id.tvAiTitle).text =
            if (mode == MODE_RESCUE) "AI 救场" else "老师讲到哪了"
        v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAiSend).setOnClickListener {
            val q = v.findViewById<EditText>(R.id.etFollowup).text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            v.findViewById<EditText>(R.id.etFollowup).setText("")
            runAi(q)
        }
        sheet.show()
        // 直接展开，避免停在半开的 peek 状态
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        runAi(null)
    }

    private fun runAi(followup: String?) {
        val ctx = requireContext()
        aiStatus?.text = if (followup == null) "思考中…" else "回答中…"
        Thread {
            try {
                val svc = (activity as? MainActivity)?.service
                val resultText: String
                if (followup == null && aiMode == MODE_RESCUE) {
                    val t = svc?.recentTranscript(2).orEmpty()
                    if (t.isBlank()) {
                        postAi("最近 2 分钟还没有转录内容。开始监听并等老师讲话后再试。", "")
                        return@Thread
                    }
                    val r = AiAssistant.rescue(ctx, t)
                    lastRescue = r
                    aiHistory = mutableListOf()
                    resultText = AiAssistant.formatRescue(r)
                } else if (followup == null) {
                    val t = svc?.recentTranscript(10).orEmpty()
                    if (t.isBlank()) {
                        postAi("最近 10 分钟还没有转录内容。开始监听并等老师讲话后再试。", "")
                        return@Thread
                    }
                    val s = AiAssistant.catchup(ctx, t)
                    lastSummary = s
                    aiHistory = mutableListOf()
                    resultText = s
                } else if (aiMode == MODE_RESCUE) {
                    val r = lastRescue ?: AiAssistant.RescueResult("", "", "（无第一轮结果）")
                    val a = AiAssistant.rescueChat(
                        ctx, r, svc?.recentTranscript(10).orEmpty(), aiHistory, followup
                    )
                    aiHistory.add(LlmClient.ChatMsg("user", followup))
                    aiHistory.add(LlmClient.ChatMsg("assistant", a))
                    resultText = AiAssistant.formatRescue(r) + "\n\n―― 追问 ――\n问：$followup\n答：$a"
                } else {
                    val s = lastSummary ?: "（无进度摘要）"
                    val a = AiAssistant.catchupChat(
                        ctx, s, svc?.recentTranscript(10).orEmpty(), aiHistory, followup
                    )
                    aiHistory.add(LlmClient.ChatMsg("user", followup))
                    aiHistory.add(LlmClient.ChatMsg("assistant", a))
                    resultText = (s + "\n\n―― 追问 ――\n问：$followup\n答：$a")
                }
                postAi(resultText, "")
            } catch (e: Exception) {
                postAi("", "✗ ${e.message}")
            }
        }.apply { name = "classfox-ai"; start() }
    }

    private fun postAi(bodyText: String, statusText: String) {
        val act = activity ?: return
        act.runOnUiThread {
            if (_b == null || aiSheet == null) return@runOnUiThread
            if (bodyText.isNotEmpty()) {
                aiBody?.text = bodyText
                aiStatus?.text = ""
                aiBody?.post {
                    (aiBody?.parent as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
                }
            }
            if (statusText.isNotEmpty()) aiStatus?.text = statusText
        }
    }

    // ---------- 字幕渲染 ----------
    private fun isNearBottom(sv: android.widget.ScrollView): Boolean {
        val child = sv.getChildAt(0) ?: return true
        return child.bottom - (sv.height + sv.scrollY) < 60
    }

    private fun renderCaption() {
        if (_b == null) return
        // 保守模式的字幕正文 = 精修定稿句；流式 partial 无标点且必被精修覆盖，
        // 同屏展示即"即出+精修双显"，故只在激进模式显示 partial（延迟预算 5~10s）
        val showPartial =
            Prefs.splitMode(requireContext()) == SentenceSplitter.MODE_SPLIT_FAST
        val body = CaptionBuffer.history.toString() + if (showPartial) CaptionBuffer.partial else ""
        b.tvCaption.text = body.ifBlank { "实时字幕将在这里显示…" }
        if (captionAtBottom) scrollToBottom()
    }

    private fun scrollToBottom() {
        b.captionScroll.post {
            if (_b == null) return@post
            val child = b.captionScroll.getChildAt(0) ?: return@post
            b.captionScroll.scrollTo(0, (child.height - b.captionScroll.height).coerceAtLeast(0))
        }
    }
}
