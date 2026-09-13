package com.classfox.droid

import android.content.Context

/** 轻量配置存取（SharedPreferences） */
object Prefs {
    private const val FILE = "classfox_prefs"
    const val KEY_MOCK_ASR = "mock_asr" // 旧版两态开关，仅作迁移
    const val KEY_ASR_MODE = "asr_mode"
    const val ASR_MOCK = "mock"
    const val ASR_SYSTEM = "system"
    const val ASR_OFFLINE = "offline"
    const val KEY_SPLIT_MODE = "split_mode"
    const val KEY_THRESHOLD = "threshold_db"

    fun get(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 识别引擎三态；旧版 boolean 迁移：false→offline, true→mock */
    fun asrMode(ctx: Context): String =
        get(ctx).getString(KEY_ASR_MODE, null)
            ?: if (get(ctx).getBoolean(KEY_MOCK_ASR, true)) ASR_MOCK else ASR_OFFLINE

    fun setAsrMode(ctx: Context, mode: String) = get(ctx).edit().putString(KEY_ASR_MODE, mode).apply()

    fun isMockAsr(ctx: Context): Boolean = asrMode(ctx) == ASR_MOCK
    fun setMockAsr(ctx: Context, v: Boolean) = setAsrMode(ctx, if (v) ASR_MOCK else ASR_OFFLINE)

    fun splitMode(ctx: Context): Int = get(ctx).getInt(KEY_SPLIT_MODE, SentenceSplitter.MODE_SPLIT_FAST)
    fun setSplitMode(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_SPLIT_MODE, v).apply()

    fun thresholdDb(ctx: Context): Int = get(ctx).getInt(KEY_THRESHOLD, -30)
    fun setThresholdDb(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_THRESHOLD, v).apply()
}
