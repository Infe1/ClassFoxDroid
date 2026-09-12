package com.classfox.droid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.classfox.droid.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: RecorderService? = null
    private var bound = false
    private var isRunning = false
    private var isPaused = false
    private var logCount = 0


    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants[Manifest.permission.RECORD_AUDIO] == true
            if (ok) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    grants[Manifest.permission.POST_NOTIFICATIONS] != true
                ) {
                    // 通知权限非必须,提示即可
                }
                appendLog("✅ 麦克风权限已授予,可开始监听")
            } else {
                appendLog("❌ 未授予麦克风权限,无法录音")
            }
        }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as RecorderService.RecBinder
            service = b.service()
            bound = true
            b.service().stateListener = { rms, secs, event, text, file ->
                runOnUiThread { updateUi(rms, secs, event, text, file) }
            }
            appendLog("🔗 已连接录音服务")
            // 同步初始状态
            if (b.service().isActive()) {
                isRunning = true
                renderRunning()
            } else {
                isRunning = false
                renderRunning()
            }
            b.service().setVoiceThreshold(thresholdDb.toDouble())

        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            // 服务意外断开视为已停止
            isRunning = false
            renderRunning()
        }
    }

    private var thresholdDb = -30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.thresholdSeek.max = 50
        binding.thresholdSeek.progress = 30
        binding.thresholdSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                thresholdDb = -10 - progress
                binding.tvThreshold.text = "人声事件阈值：${thresholdDb} dB（只记录高于此值的声音）"
                service?.setVoiceThreshold(thresholdDb.toDouble())
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })

        binding.btnStart.setOnClickListener { startListening() }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnStop.setOnClickListener { stopListening() }


        // 接收服务事件广播:句子定稿 / 关键词命中 / 已停止,全部实时显示
        eventReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val kind = intent?.getStringExtra(RecorderService.EXTRA_EVENT) ?: return
                val text = intent.getStringExtra(RecorderService.EXTRA_TEXT) ?: ""
                when (kind) {
                    "stopped" -> {
                        isRunning = false
                        isPaused = false
                        renderRunning()
                        appendLog("⏹ 录音服务已停止")
                    }
                    "danger_alert" -> appendLog("🚨 $text")
                    "warning_alert" -> appendLog("⚠️ $text")
                    "sentence" -> appendLog("📝 $text")
                    "sentence_corrected" -> appendLog("✏️ $text")
                    "voice" -> appendLog("🎙 $text")
                    "partial" -> binding.tvCaption.text = text
                    "paused" -> { isPaused = true; renderRunning(); appendLog(text) }
                    "resumed" -> { isPaused = false; renderRunning(); appendLog(text) }
                }
            }
        }
        registerReceiver(eventReceiver, IntentFilter(RecorderService.ACTION_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        // 仅当服务已在运行时绑定(不自动启动)
        bindService(Intent(this, RecorderService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private var eventReceiver: BroadcastReceiver? = null


    override fun onResume() {
        super.onResume()
        // 每次回到前台,向服务查询实时状态
        service?.let { s ->
            if (s.isActive()) { isRunning = true } else { isRunning = false }
            renderRunning()
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(
                if (Build.VERSION.SDK_INT >= 33)
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else
                    arrayOf(Manifest.permission.RECORD_AUDIO)
            )
            return
        }
        if (!isRunning) {
            val i = Intent(this, RecorderService::class.java)
            ContextCompat.startForegroundService(this, i)
            // 立即置为运行态;若权限等导致服务启动失败,onServiceDisconnected/stopped 广播会复位
            isRunning = true
            isPaused = false
            renderRunning()
            appendLog("▶️ 启动课堂监听…")
        }
    }

    private fun stopListening() {
        if (!isRunning) return
        val i = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
        startService(i)
        // 以用户意图为准立即复位;服务端 onServiceDisconnected/stopped 广播兜底
        isRunning = false
        renderRunning()
        binding.tvDb.text = "-90 dB"
        binding.volumeBar.progress = 0
        appendLog("⏹ 停止监听")
    }

    private fun togglePause() {
        if (!isRunning) return
        val action = if (isPaused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
        startService(Intent(this, RecorderService::class.java).setAction(action))
    }

    private fun renderRunning() {
        binding.btnStart.isEnabled = !isRunning
        binding.btnPause.isEnabled = isRunning
        binding.btnStop.isEnabled = isRunning
        binding.btnPause.text = if (isPaused) "继续" else "暂停"
        binding.tvStatus.text = when {
            !isRunning -> "⚪ 未监听"
            isPaused -> "⏸ 已暂停"
            else -> "🟢 监听中"
        }
    }

    private fun updateUi(rmsDb: Double, secs: Int, event: String?, text: String?, file: String?) {
        // 声浪条(0~-90 → 0..100)
        val pct = ((rmsDb + 90) / 90.0 * 100).toFloat().coerceIn(0f, 100f)
        binding.volumeBar.progress = pct.toInt()
        binding.tvDb.text = String.format("%.0f dB", rmsDb)
        binding.tvTime.text = "已监听 %02d:%02d".format(secs / 60, secs % 60)
        if (file != null) binding.tvFile.text = file.substringAfterLast('/')
        // 颜色:响→红
        val color = when {
            rmsDb > -30 -> Color.rgb(229, 57, 53)
            rmsDb > -45 -> Color.rgb(251, 140, 0)
            else -> Color.rgb(102, 187, 106)
        }
        binding.volumeBar.progressTintList =
            android.content.res.ColorStateList.valueOf(color)

    }

    private fun appendLog(msg: String) {
        logCount++
        binding.tvLog.append("${System.currentTimeMillis().toString().takeLast(6)} $msg\n")
        // 滚动到日志底部(滚 ScrollView,而非 TextView 自身)
        binding.tvLog.post {
            val sv = binding.tvLog.parent?.parent as? android.widget.ScrollView
            sv?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.stateListener = null
            unbindService(conn)
            bound = false
        }
        if (eventReceiver != null) {
            try { unregisterReceiver(eventReceiver) } catch (_: Exception) {}
            eventReceiver = null
        }
        super.onDestroy()
    }
}
