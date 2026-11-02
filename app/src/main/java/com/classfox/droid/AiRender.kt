package com.classfox.droid

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * AI 输出（Markdown + LaTeX 公式）→ TextView 渲染（v0.11.3）。
 * - Markwon span 渲染，离线可用，不引 WebView；
 * - JLatexMathPlugin：$$..$$ 块级与 $..$ 行内（inlinesEnabled 默认关，必须显式开）；
 *   **硬依赖 MarkwonInlineParserPlugin**（configure 时 registry.require，不注册直接抛）；
 * - \[..\] / \(..\) 由 normalizeLatex 预处理转换；
 * - 单个坏公式由 errorHandler 吞成透明占位；整体渲染异常退回纯文本原文并记日志。
 */
object AiRender {

    private const val TAG = "AiRender"

    @Volatile private var markwon: Markwon? = null

    private fun obtain(ctx: Context, textSizePx: Float): Markwon =
        markwon ?: Markwon.builder(ctx.applicationContext)
            .usePlugin(MarkwonInlineParserPlugin.create()) // JLatexMathPlugin 硬依赖，必须先注册
            .usePlugin(TablePlugin.create(ctx))
            .usePlugin(
                JLatexMathPlugin.create(
                    JLatexMathPlugin.builder(textSizePx)
                        .inlinesEnabled(true)
                        .blocksEnabled(true)
                        .errorHandler { latex, _ -> ColorDrawable(0x00000000) } // 坏公式→透明占位
                        .build()
                )
            )
            .build()
            .also { markwon = it }

    /** \[..\] → 块级 $$..$$；\(..\) → 行内 $..$（LLM 两种写法都会出） */
    private fun normalizeLatex(s: String): String = s
        .replace(Regex("\\\\\\[([\\s\\S]*?)\\\\\\]")) { m -> "\n$$\n${m.groupValues[1]}\n$$\n" }
        .replace(Regex("\\\\\\(([\\s\\S]*?)\\\\\\)")) { m -> "\$${m.groupValues[1]}\$" }

    /** 渲染到 TextView；任何异常退回原文纯文本并记日志 */
    fun render(tv: TextView, raw: String) {
        val md = try { normalizeLatex(raw) } catch (t: Throwable) { Log.w(TAG, "normalize 失败", t); raw }
        try {
            obtain(tv.context, tv.textSize).setMarkdown(tv, md)
        } catch (t: Throwable) {
            Log.w(TAG, "渲染失败，退回纯文本", t)
            tv.text = raw
        }
    }
}
