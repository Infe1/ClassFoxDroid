package com.classfox.droid

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.classfox.droid.databinding.ActivityMainBinding

/**
 * 单 Activity 外壳：工具栏 + Fragment 容器 + 底部导航（监听/记录/设置）。
 * 服务绑定与事件分发集中在这里；Fragment 通过 sink 回调拿事件。
 */
class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
        private set
    var service: RecorderService? = null
        private set

    /** 监听页注册的文本事件 sink（句/警报/字幕/状态） */
    var listenSink: ((kind: String, text: String) -> Unit)? = null

    /** 监听页注册的状态 sink（音量/计时/文件名） */
    var stateSink: ((rms: Double, secs: Int, file: String?) -> Unit)? = null

    private var bound = false

    private val conn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) {
            service = (binder as RecorderService.RecBinder).service()
            bound = true
            service?.stateListener = { rms, secs, file ->
                runOnUiThread { stateSink?.invoke(rms, secs, file) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val kind = intent?.getStringExtra(RecorderService.EXTRA_EVENT) ?: return
            val text = intent.getStringExtra(RecorderService.EXTRA_TEXT) ?: ""
            listenSink?.invoke(kind, text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerReceiver(
            eventReceiver, IntentFilter(RecorderService.ACTION_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bindService(
            Intent(this, RecorderService::class.java), conn,
            Context.BIND_AUTO_CREATE
        )

        if (savedInstanceState == null) show(ListenFragment())
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_listen -> show(ListenFragment())
                R.id.nav_records -> show(RecordsFragment())
                R.id.nav_settings -> show(SettingsFragment())
            }
            true
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
    }

    override fun onDestroy() {
        if (bound) {
            service?.stateListener = null
            unbindService(conn)
            bound = false
        }
        try { unregisterReceiver(eventReceiver) } catch (_: Exception) {}
        listenSink = null
        stateSink = null
        super.onDestroy()
    }
}
