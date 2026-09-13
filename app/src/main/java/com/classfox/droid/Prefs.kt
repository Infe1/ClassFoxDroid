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
    const val KEY_FORCE_PORTRAIT = "force_portrait"
    const val KEY_ASR_MODEL = "asr_model"
    const val MODEL_SMALL = "zh14m"
    const val MODEL_LARGE = "zh2025"

    /** 离线识别模型；默认高精度（真实课堂语音下小模型误识别严重） */
    fun asrModel(ctx: Context): String = get(ctx).getString(KEY_ASR_MODEL, MODEL_LARGE) ?: MODEL_LARGE
    fun setAsrModel(ctx: Context, v: String) = get(ctx).edit().putString(KEY_ASR_MODEL, v).apply()

    // ---- AI 救场（OpenAI 兼容接口）----
    const val KEY_LLM_BASE = "llm_base_url"
    const val KEY_LLM_KEY = "llm_api_key"
    const val KEY_LLM_MODEL = "llm_model"

    fun llmBase(ctx: Context): String = get(ctx).getString(KEY_LLM_BASE, "")!!.trim()
    fun llmKey(ctx: Context): String = get(ctx).getString(KEY_LLM_KEY, "")!!.trim()
    fun llmModel(ctx: Context): String = get(ctx).getString(KEY_LLM_MODEL, "")!!.trim()
    fun setLlm(ctx: Context, base: String, key: String, model: String) =
        get(ctx).edit().putString(KEY_LLM_BASE, base.trim())
            .putString(KEY_LLM_KEY, key.trim())
            .putString(KEY_LLM_MODEL, model.trim()).apply()

    fun llmReady(ctx: Context): Boolean = llmBase(ctx).isNotEmpty() && llmModel(ctx).isNotEmpty()

    // ---- 课程历史（课程名下拉选择用）----
    const val KEY_COURSE_HISTORY = "course_history"

    fun courseHistory(ctx: Context): Set<String> =
        get(ctx).getStringSet(KEY_COURSE_HISTORY, emptySet()) ?: emptySet()

    fun addCourseHistory(ctx: Context, name: String) {
        if (name.isBlank()) return
        val s = courseHistory(ctx).toMutableSet()
        s.add(name.trim())
        get(ctx).edit().putStringSet(KEY_COURSE_HISTORY, s).apply()
    }

    fun forcePortrait(ctx: Context): Boolean = get(ctx).getBoolean(KEY_FORCE_PORTRAIT, true)
    fun setForcePortrait(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean(KEY_FORCE_PORTRAIT, v).apply()

    fun get(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 识别引擎三态；默认离线识别（真机开箱即用），模拟识别变可选项 */
    fun asrMode(ctx: Context): String =
        get(ctx).getString(KEY_ASR_MODE, null) ?: ASR_OFFLINE

    fun setAsrMode(ctx: Context, mode: String) = get(ctx).edit().putString(KEY_ASR_MODE, mode).apply()

    fun isMockAsr(ctx: Context): Boolean = asrMode(ctx) == ASR_MOCK
    fun setMockAsr(ctx: Context, v: Boolean) = setAsrMode(ctx, if (v) ASR_MOCK else ASR_OFFLINE)

    fun splitMode(ctx: Context): Int = get(ctx).getInt(KEY_SPLIT_MODE, SentenceSplitter.MODE_SPLIT_FAST)
    fun setSplitMode(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_SPLIT_MODE, v).apply()

    fun thresholdDb(ctx: Context): Int = get(ctx).getInt(KEY_THRESHOLD, -30)
    fun setThresholdDb(ctx: Context, v: Int) = get(ctx).edit().putInt(KEY_THRESHOLD, v).apply()
}
