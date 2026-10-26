package com.classfox.droid

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 内层可滚动字幕容器（替代 NestedScrollView 的轻量方案）。
 *
 * 场景：监听页外层是整页 ScrollView，字幕卡内层也是 ScrollView，同向嵌套默认冲突
 * （手指在内层滑动时外层整页跟着滚，字幕无法独立回看）。
 * 做法：ACTION_DOWN 起请求父级不拦截，抬手/取消时归还 —— 内层滚到头也不影响外层。
 * 不用 androidx.core NestedScrollView：其构造器在部分模拟器（MuMu 翻译层）上
 * 触发 mChildHelper 未初始化的 NPE，framework ScrollView 无此风险。
 */
class CaptionScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    // DOWN 在 onInterceptTouchEvent 里就要请求：字幕 TextView 可选中(textIsSelectable)，
    // 会消费 DOWN，onTouchEvent 未必执行；而外层在 MOVE 时就会判定拦截，必须赶在这之前。
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(ev)
    }

    /** 滑动加速度（用户反馈：长转录"划不动"——默认 fling 摩擦太大，一甩走不了多远）：
     *  初速度放大 2.2 倍，长文本一甩能走大半屏；监听页字幕回看与转录对话框共用本类。 */
    override fun fling(velocityY: Int) {
        super.fling((velocityY * 2.2f).toInt())
    }
}
