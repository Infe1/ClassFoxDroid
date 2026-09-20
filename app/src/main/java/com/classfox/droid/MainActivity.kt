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
        applyOrientation()

        registerReceiver(
            eventReceiver, IntentFilter(RecorderService.ACTION_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bindService(
            Intent(this, RecorderService::class.java), conn,
            Context.BIND_AUTO_CREATE
        )

        if (savedInstanceState == null) {
            show(ListenFragment(), TAG_LISTEN)
        } else {
            // 重建路径：FM 自动恢复的三个 fragment 均可见，按恢复的选中 tab 归位
            when (binding.bottomNav.selectedItemId) {
                R.id.nav_records -> show(RecordsFragment(), TAG_RECORDS)
                R.id.nav_settings -> show(SettingsFragment(), TAG_SETTINGS)
                else -> show(ListenFragment(), TAG_LISTEN)
            }
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_listen -> show(ListenFragment(), TAG_LISTEN)
                R.id.nav_records -> show(RecordsFragment(), TAG_RECORDS)
                R.id.nav_settings -> show(SettingsFragment(), TAG_SETTINGS)
            }
            true
        }
    }

    /** 默认强制竖屏（手机习惯）；设置页可切回跟随系统做横竖对比 */
    fun applyOrientation() {
        requestedOrientation =
            if (Prefs.forcePortrait(this))
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    /**
     * tab 切换用 add/show/hide 复用实例（v0.10.2 修复"切 tab 后字幕消失"）。
     * 原 replace+new 会重建 ListenFragment，captionHistory 内存态全丢；
     * show/hide 同时让监听页隐藏期间 sink 仍挂着，后台句子的字幕不丢。
     * Activity 重建时 FragmentManager 会把三个都恢复为可见，这里按当前 tab 重新归位。
     */
    private fun show(f: Fragment, tag: String) {
        val fm = supportFragmentManager
        val target = fm.findFragmentByTag(tag) ?: f
        val tx = fm.beginTransaction()
        fm.fragments.forEach { if (it !== target) tx.hide(it) }
        if (target.isAdded) tx.show(target) else tx.add(R.id.container, target, tag)
        tx.commit()
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

    private companion object {
        const val TAG_LISTEN = "listen"
        const val TAG_RECORDS = "records"
        const val TAG_SETTINGS = "settings"
    }
}
