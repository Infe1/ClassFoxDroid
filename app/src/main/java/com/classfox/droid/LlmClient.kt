package com.classfox.droid

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容 /chat/completions 客户端（非流式）。
 * 兼容各类中转/本地服务（Ollama、one-api、DeepSeek 等），地址/Key/模型存 Prefs。
 */
object LlmClient {

    class LlmException(msg: String) : Exception(msg)

    class ChatMsg(val role: String, val content: String)

    /** 阻塞式调用，务必在后台线程执行 */
    fun chat(
        ctx: android.content.Context,
        system: String,
        user: String,
        history: List<ChatMsg> = emptyList(),
        maxTokens: Int = 800,
        temperature: Double = 0.3
    ): String {
        val base = Prefs.llmBase(ctx)
        val model = Prefs.llmModel(ctx)
        val key = Prefs.llmKey(ctx)
        if (base.isEmpty() || model.isEmpty()) {
            throw LlmException("未配置大模型接口（设置 → AI 救场）")
        }

        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        for (m in history) msgs.put(JSONObject().put("role", m.role).put("content", m.content))
        msgs.put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw LlmException("HTTP $code：${text.take(300)}")

            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return content.trim()
        } finally {
            conn?.disconnect()
        }
    }
}
