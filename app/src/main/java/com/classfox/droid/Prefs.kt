package com.classfox.droid

import android.content.Context

/** 轻量配置存取（SharedPreferences） */
object Prefs {
    private const val FILE = "classfox_prefs"
    const val KEY_MOCK_ASR = "mock_asr"
    const val KEY_SPLIT_MODE = "split_mode"
    const val KEY_THRESHOLD = "threshold_db"

    fun get(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isMockAsr(ctx: Context): Boolean = get(ctx).getBoolean(KEY_MOCK_ASR, true)
    fun setMockAsr(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean(KEY_MOCK_ASR, v).apply()

    fun splitMode(ctx: Context): Int = get(ctx).getInt(KEY_SPLIT_MODE, SentenceSplitter.MODE_SPLIT_FAST)
    fun setSplitMode(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_SPLIT_MODE, v).apply()

    fun thresholdDb(ctx: Context): Int = get(ctx).getInt(KEY_THRESHOLD, -30)
    fun setThresholdDb(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_THRESHOLD, v).apply()
}
