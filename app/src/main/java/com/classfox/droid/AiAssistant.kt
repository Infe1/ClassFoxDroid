package com.classfox.droid

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * AI 救场 / 老师讲到哪了（对齐桌面版 llm_service 行为）：
 * - 救场：最近 2 分钟转录（+课程资料）→ LLM 输出 JSON {context, question, answer}
 * - 讲到哪了：最近 10 分钟转录 → ≤200 字进度摘要
 * - 两种追问：携带上一轮结果 + 最多 8 条历史
 */
object AiAssistant {

    private const val TAG = "ClassFoxAi"

    data class RescueResult(val context: String, val question: String, val answer: String)

    private const val RESCUE_SYS =
        "你是课堂助教。根据老师最近的讲话转录（以及课程资料，如果提供），" +
        "判断老师此刻是否在向学生提问或点名，并给出该问题的参考答案。" +
        "只输出一个 JSON 对象，不要 markdown 代码块：" +
        "{\"context\":\"从转录中摘录的老师原话\",\"question\":\"老师提出的问题（若非提问则给出最可能被问的问题）\",\"answer\":\"简明准确的答案，面向学生的口吻\"}"

    private const val RESCUE_CHAT_SYS =
        "你是课堂助教。学生刚被课堂提问并已获得第一轮参考回答，学生可能继续追问。" +
        "请基于第一轮结果、最近转录与历史对话，用简明中文回答学生的追问。"

    private const val CATCHUP_SYS =
        "你是课堂助教。请根据最近课堂转录，用不超过200字总结老师当前讲到哪了、" +
        "正在进行的主题与要点。直接输出摘要文本，不要任何前后缀。"

    private const val CATCHUP_CHAT_SYS =
        "你是课堂助教。学生想进一步了解课堂进度。基于已有进度摘要、最近转录与历史对话，用简明中文回答。"

    private fun materialText(ctx: Context): String = try {
        val f = java.io.File(ctx.getExternalFilesDir(null), "classfox/current_class_material.txt")
        if (f.exists()) f.readText().take(4000) else "（无）"
    } catch (_: Exception) { "（无）" }

    /** 救场：阻塞调用，后台线程执行 */
    fun rescue(ctx: Context, transcript: String): RescueResult {
        val user = "最近课堂转录：\n${transcript.ifBlank { "（近两分钟没有转录内容）" }}\n\n" +
            "课程资料：\n${materialText(ctx)}\n\n请按系统要求输出 JSON。"
        val raw = LlmClient.chat(ctx, RESCUE_SYS, user, maxTokens = 800, temperature = 0.3)
        Log.i(TAG, "rescue raw: ${raw.take(200)}")
        return parseRescue(raw)
    }

    private fun parseRescue(raw: String): RescueResult {
        return try {
            val body = raw.substringAfter("{").substringBeforeLast("}")
            val o = JSONObject("{$body}")
            RescueResult(
                context = o.optString("context", ""),
                question = o.optString("question", ""),
                answer = o.optString("answer", "")
            )
        } catch (e: Exception) {
            // JSON 解析失败：整段作为答案兜底（对齐桌面版）
            RescueResult(context = "", question = "", answer = raw)
        }
    }

    /** 救场追问 */
    fun rescueChat(
        ctx: Context, prev: RescueResult, transcript: String,
        history: List<LlmClient.ChatMsg>, followup: String
    ): String {
        val user = "第一轮结果：\n原文：${prev.context}\n问题：${prev.question}\n答案：${prev.answer}\n\n" +
            "最近转录：\n${transcript.ifBlank { "（无）" }}\n\n学生的追问：$followup"
        return LlmClient.chat(ctx, RESCUE_CHAT_SYS, user, history.takeLast(8), maxTokens = 800, temperature = 0.4)
    }

    /** 讲到哪了 */
    fun catchup(ctx: Context, transcript: String): String {
        val user = "最近课堂转录：\n${transcript.ifBlank { "（近十分钟没有转录内容）" }}"
        return LlmClient.chat(ctx, CATCHUP_SYS, user, maxTokens = 400, temperature = 0.3)
    }

    /** 进度追问 */
    fun catchupChat(
        ctx: Context, summary: String, transcript: String,
        history: List<LlmClient.ChatMsg>, followup: String
    ): String {
        val user = "进度摘要：$summary\n\n最近转录：\n${transcript.ifBlank { "（无）" }}\n\n学生的追问：$followup"
        return LlmClient.chat(ctx, CATCHUP_CHAT_SYS, user, history.takeLast(8), maxTokens = 600, temperature = 0.4)
    }

    fun formatRescue(r: RescueResult): String = buildString {
        if (r.context.isNotBlank()) append("【老师原话】\n${r.context}\n\n")
        if (r.question.isNotBlank()) append("【问题】\n${r.question}\n\n")
        append("【参考答案】\n${r.answer}")
    }
}
