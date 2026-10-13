package com.cshdotcom.selkies.input

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cshdotcom.selkies.connection.Keysyms
import com.cshdotcom.selkies.connection.Protocol
import com.cshdotcom.selkies.connection.SelkiesClient

/**
 * 触摸手势 → Selkies 鼠标事件：
 * - 单指轻点：左键单击（按下→抬起，先发指针移动）
 * - 单指拖动：按住左键移动（超过 slop 阈值后进入拖拽）
 * - 双击：左键双击
 * - 长按：右键（可配置为：0=右键 1=中键 2=无）
 * - 双指垂直/水平滑动：滚轮（像素累积到 scrollStep 触发一档）
 * - 双指捏合：本地缩放画面（回调 onZoom）
 * 坐标经 mapper：屏幕像素 → 远端流像素。
 */
class TouchGestureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        /** 屏幕 (x,y) → 远端 (rx,ry) */
        fun mapToRemote(x: Float, y: Float): Pair<Int, Int>
        /** 本地缩放（捏合） */
        fun onZoom(factor: Float, pivotX: Float, pivotY: Float)
    }

    var client: SelkiesClient? = null
    var callback: Callback? = null

    // 手势参数（可由设置覆写）
    var tapSlopPx: Int = 24
    var longPressMs: Long = 500
    var longPressAction: Int = 0        // 0=右键 1=中键 2=无
    var naturalScroll: Boolean = false
    var scrollStepPx: Int = 50
    var tapMaxMs: Long = 220

    private val handler = Handler(Looper.getMainLooper())

    // 单指状态
    private var downX = 0f; private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private var longFired = false
    private var lastTapAt = 0L
    private var lastTapX = 0f; private var lastTapY = 0f

    // 双指状态
    private var twoFinger = false
    private var pinchBaseDist = 0f
    private var pinchMode = false
    private var scrollAccumY = 0f
    private var scrollAccumX = 0f
    private var lastMidX = 0f; private var lastMidY = 0f

    private val longRunnable = Runnable {
        if (!twoFinger && !dragging) {
            longFired = true
            val (rx, ry) = cbMap(downX, downY)
            when (longPressAction) {
                0 -> { clickRemote(Protocol.MASK_RIGHT, rx, ry) }
                1 -> { clickRemote(Protocol.MASK_MIDDLE, rx, ry) }
            }
        }
    }

    private fun cbMap(x: Float, y: Float): Pair<Int, Int> =
        callback?.mapToRemote(x, y) ?: (0 to 0)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = client ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downAt = System.currentTimeMillis()
                dragging = false; longFired = false; twoFinger = false
                handler.postDelayed(longRunnable, longPressMs)
                // 移动指针到位（与浏览器 mousemove → mousedown 顺序一致）
                val (rx, ry) = cbMap(e.x, e.y)
                c.moveWithMask(rx, ry)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    enterTwoFinger(e)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && e.pointerCount >= 2) {
                    onTwoFingerMove(e)
                } else if (e.pointerCount == 1 && !longFired) {
                    onSingleMove(e)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFinger) {
                    // 双指结束：抬起其中一根后回到单指轨迹（不再触发点击）
                    exitTwoFinger()
                    dragging = false
                    longFired = true   // 防止抬指被当作点击
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                val dt = System.currentTimeMillis() - downAt
                val dist = kotlin.math.hypot(e.x - downX, e.y - downY)
                if (dragging) {
                    c.buttonUp(Protocol.MASK_LEFT)
                } else if (!longFired && !twoFinger && dt < tapMaxMs && dist < tapSlopPx * 2) {
                    // 点击：判定双击
                    val now = System.currentTimeMillis()
                    val isDouble = now - lastTapAt < 300 &&
                        kotlin.math.hypot(e.x - lastTapX, e.y - lastTapY) < tapSlopPx * 2
                    val (rx, ry) = cbMap(e.x, e.y)
                    clickRemote(Protocol.MASK_LEFT, rx, ry)
                    if (isDouble) clickRemote(Protocol.MASK_LEFT, rx, ry)
                    lastTapAt = now; lastTapX = e.x; lastTapY = e.y
                }
                twoFinger = false; dragging = false; longFired = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                if (dragging) c.buttonUp(Protocol.MASK_LEFT)
                exitTwoFinger()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun onSingleMove(e: MotionEvent) {
        val c = client ?: return
        if (!dragging) {
            if (kotlin.math.hypot(e.x - downX, e.y - downY) > tapSlopPx) {
                dragging = true
                handler.removeCallbacks(longRunnable)
                c.buttonDown(Protocol.MASK_LEFT)
            } else {
                return
            }
        }
        val (rx, ry) = cbMap(e.x, e.y)
        c.moveWithMask(rx, ry)
    }

    private fun enterTwoFinger(e: MotionEvent) {
        handler.removeCallbacks(longRunnable)
        if (dragging) { client?.buttonUp(Protocol.MASK_LEFT); dragging = false }
        twoFinger = true; pinchMode = false
        pinchBaseDist = distance(e)
        val mid = mid(e)
        lastMidX = mid.first; lastMidY = mid.second
        scrollAccumY = 0f; scrollAccumX = 0f
    }

    private fun onTwoFingerMove(e: MotionEvent) {
        val dist = distance(e)
        val mid = mid(e)
        if (!pinchMode && kotlin.math.abs(dist - pinchBaseDist) > pinchBaseDist * 0.12f) {
            pinchMode = true
        }
        if (pinchMode) {
            // 捏合缩放 + 双指平移
            val factor = dist / (pinchBaseDist.takeIf { it > 1f } ?: 1f)
            callback?.onZoom(factor, mid.first, mid.second)
            pinchBaseDist = dist
        } else {
            // 滚轮：累积中点位移
            val dy = mid.second - lastMidY
            val dx = mid.first - lastMidX
            scrollAccumY += dy
            scrollAccumX += dx
            if (naturalScroll) { scrollAccumY = -scrollAccumY; scrollAccumX = -scrollAccumX }
            while (kotlin.math.abs(scrollAccumY) >= scrollStepPx) {
                val dir = if (scrollAccumY > 0) SelkiesClient.ScrollDir.DOWN else SelkiesClient.ScrollDir.UP
                client?.scrollPulse(dir)
                scrollAccumY -= if (scrollAccumY > 0) scrollStepPx else -scrollStepPx
            }
            while (kotlin.math.abs(scrollAccumX) >= scrollStepPx * 2) {
                val dir = if (scrollAccumX > 0) SelkiesClient.ScrollDir.RIGHT else SelkiesClient.ScrollDir.LEFT
                client?.scrollPulse(dir)
                scrollAccumX -= if (scrollAccumX > 0) scrollStepPx * 2 else -scrollStepPx * 2
            }
            if (naturalScroll) { /* 已在方向上生效 */ }
        }
        lastMidX = mid.first; lastMidY = mid.second
    }

    private fun exitTwoFinger() {
        twoFinger = false; pinchMode = false
        scrollAccumX = 0f; scrollAccumY = 0f
    }

    private fun clickRemote(maskBit: Int, rx: Int, ry: Int) {
        val c = client ?: return
        c.moveWithMask(rx, ry)
        c.buttonDown(maskBit)
        c.buttonUp(maskBit)
    }

    private fun distance(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return kotlin.math.hypot(dx, dy)
    }

    private fun mid(e: MotionEvent): Pair<Float, Float> =
        ((e.getX(0) + e.getX(1)) / 2f) to ((e.getY(0) + e.getY(1)) / 2f)
}
