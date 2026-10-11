package com.selkies.client.input

import android.view.MotionEvent
import com.selkies.client.data.SettingsStore
import com.selkies.client.net.ConnectionEngine

/**
 * 触摸手势处理器 —— 把 Android 触摸事件翻译为 Selkies 输入协议。
 *
 * 两种模式（对齐主流远程桌面 App 的操作习惯）：
 *
 * **direct（直接触摸）** —— 手指位置即鼠标绝对位置：
 *   - 单指轻点 = 左键点击；单指拖动 = 左键按住拖拽
 *   - 长按（≥450ms 未移动）= 可配置（右键 / 中键 / 继续拖拽）
 *   - 双指轻点 = 可配置（右键 / 中键）
 *   - 双指竖直拖动 = 滚动
 *   - 双指捏合 = 本地缩放（可关）
 *
 * **trackpad（触摸板）** —— 手指滑动为相对位移（m2）：
 *   - 单指滑动 = 移动鼠标（带灵敏度增益）
 *   - 单指轻点 = 左键；长按后滑动 = 按住左键拖拽
 *   - 双指轻点 = 右键（可配置）
 *   - 双指滑动 = 滚动
 *
 * 滚动双路径：服务端 Wayland finger_scroll 可用时发 sf/sfe（平滑，流像素），
 * 否则回退滚轮脉冲 m2（X11 兼容）。
 *
 * 所有坐标换算基于 view↔stream 映射（[setMapping]）。
 */
class GestureHandler(
    private val engine: ConnectionEngine,
    private val input: InputController,
    private val settings: SettingsStore
) {

    // ---- view ↔ stream 坐标映射（SessionActivity 在布局/分辨率变化时更新） ----
    @Volatile var viewW = 1f
    @Volatile var viewH = 1f
    @Volatile var contentX = 0f   // 内容区左上（letterbox 偏移）
    @Volatile var contentY = 0f
    @Volatile var contentW = 1f   // 内容区尺寸
    @Volatile var contentH = 1f
    @Volatile var streamW = 1f    // 流分辨率
    @Volatile var streamH = 1f

    /** 服务端 finger_scroll 能力（DISPLAY_CONFIG_UPDATE.finger_scroll） */
    @Volatile var fingerScrollAvailable = false

    /** 触摸灵敏度（trackpad 模式增益） */
    @Volatile var sensitivity = 1.6f

    /** 缩放回调（本地视觉缩放，SessionActivity 实现重布局） */
    var onZoom: ((factor: Float, focusX: Float, focusY: Float) -> Unit)? = null
    var onPan: ((dx: Float, dy: Float) -> Unit)? = null

    // ---- 手势状态 ----
    private var mode = Mode.NONE
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedFar = false
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null

    // 双指状态
    private var twoFingerStartDist = 0f
    private var twoFingerStartMidX = 0f
    private var twoFingerStartMidY = 0f
    private var pinchDetected = false
    private var scrollAccumY = 0f
    private var scrollAccumX = 0f
    private var twoFingerDownTime = 0L
    private var twoFingerMoved = false

    // trackpad 虚拟鼠标位置（流坐标，用于绝对化点击）
    private var curStreamX = 0f
    private var curStreamY = 0f

    private enum class Mode { NONE, DRAG_LEFT, DRAG_RIGHT, DRAG_MIDDLE, TRACKPAD_MOVE, TWO_FINGER, PINCH }

    /** view 坐标 → 流坐标 */
    private fun toStreamX(x: Float): Int {
        if (contentW <= 0f) return 0
        return (((x - contentX) / contentW) * streamW).toInt().coerceIn(0, streamW.toInt())
    }

    private fun toStreamY(y: Float): Int {
        if (contentH <= 0f) return 0
        return (((y - contentY) / contentH) * streamH).toInt().coerceIn(0, streamH.toInt())
    }

    /** view 像素 → 流像素比例 */
    private val pxScale: Float
        get() = if (contentW > 0f) streamW / contentW else 1f

    /** 供 SessionActivity 初始化虚拟鼠标位置 */
    fun initCursorPosition(cx: Float, cy: Float) {
        curStreamX = toStreamX(cx).toFloat()
        curStreamY = toStreamY(cy).toFloat()
    }

    // ------------------------------------------------------------------
    // 主入口
    // ------------------------------------------------------------------

    fun onTouchEvent(e: MotionEvent): Boolean {
        return try {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> onDown(e)
                MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
                MotionEvent.ACTION_MOVE -> onMove(e)
                MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
                MotionEvent.ACTION_UP -> onUp(e)
                MotionEvent.ACTION_CANCEL -> onCancel(e)
                else -> true
            }
        } catch (ex: Exception) {
            // 手势处理异常绝不闪退：重置状态
            reset()
            true
        }
    }

    private fun onDown(e: MotionEvent): Boolean {
        mode = Mode.NONE
        downTime = System.currentTimeMillis()
        downX = e.x; downY = e.y
        lastX = e.x; lastY = e.y
        movedFar = false
        longPressFired = false
        cancelLongPress()

        when (settings.touchMode) {
            "direct" -> {
                // 直接模式：按下即左键按下于该位置
                mode = Mode.DRAG_LEFT
                val sx = toStreamX(e.x)
                val sy = toStreamY(e.y)
                curStreamX = sx.toFloat(); curStreamY = sy.toFloat()
                input.mouseAbsolute(sx, sy, InputController.maskOf(InputController.BUTTON_LEFT))
                armLongPress()
            }
            else -> {
                // 触摸板模式：等待移动/抬起/长按判定
                mode = Mode.TRACKPAD_MOVE
                armLongPress()
            }
        }
        return true
    }

    private fun onPointerDown(e: MotionEvent): Boolean {
        if (e.pointerCount == 2) {
            cancelLongPress()
            twoFingerDownTime = System.currentTimeMillis()
            twoFingerMoved = false
            pinchDetected = false
            scrollAccumY = 0f
            scrollAccumX = 0f
            twoFingerStartDist = distance(e, 0, 1)
            twoFingerStartMidX = (e.getX(0) + e.getX(1)) / 2f
            twoFingerStartMidY = (e.getY(0) + e.getY(1)) / 2f
            mode = Mode.TWO_FINGER
            // 直接模式：抬起左键（从单指拖动切换）
            if (settings.touchMode == "direct") {
                input.mouseAbsolute(toStreamX(e.x), toStreamY(e.y), 0)
            }
        }
        return true
    }

    private fun onMove(e: MotionEvent): Boolean {
        when (mode) {
            Mode.DRAG_LEFT, Mode.DRAG_RIGHT, Mode.DRAG_MIDDLE -> {
                if (!movedFar && dist(downX, downY, e.x, e.y) > SLOP) movedFar = true
                if (movedFar) cancelLongPress()
                val mask = when (mode) {
                    Mode.DRAG_LEFT -> InputController.maskOf(InputController.BUTTON_LEFT)
                    Mode.DRAG_RIGHT -> InputController.maskOf(InputController.BUTTON_RIGHT)
                    else -> InputController.maskOf(InputController.BUTTON_MIDDLE)
                }
                if (settings.touchMode == "direct") {
                    // 直接模式：绝对坐标拖拽
                    input.mouseAbsolute(toStreamX(e.x), toStreamY(e.y), mask)
                } else {
                    // 触摸板模式的长按拖拽：相对位移 + 按住左键
                    val dx = (e.x - lastX) * sensitivity * pxScale
                    val dy = (e.y - lastY) * sensitivity * pxScale
                    input.mouseRelative(dx.toInt(), dy.toInt(), mask)
                    curStreamX = (curStreamX + dx).coerceIn(0f, streamW.toFloat())
                    curStreamY = (curStreamY + dy).coerceIn(0f, streamH.toFloat())
                }
                lastX = e.x; lastY = e.y
            }

            Mode.TRACKPAD_MOVE -> {
                if (!movedFar && dist(downX, downY, e.x, e.y) > SLOP) movedFar = true
                if (movedFar) cancelLongPress()
                val dx = (e.x - lastX) * sensitivity * pxScale
                val dy = (e.y - lastY) * sensitivity * pxScale
                lastX = e.x; lastY = e.y
                if (mode == Mode.TRACKPAD_MOVE) {
                    input.mouseRelative(dx.toInt(), dy.toInt(), 0)
                    // 维护虚拟位置（用于潜在绝对化操作）
                    curStreamX = (curStreamX + dx).coerceIn(0f, streamW.toFloat())
                    curStreamY = (curStreamY + dy).coerceIn(0f, streamH.toFloat())
                }
            }

            Mode.TWO_FINGER -> {
                if (e.pointerCount < 2) return true
                val d = distance(e, 0, 1)
                val midX = (e.getX(0) + e.getX(1)) / 2f
                val midY = (e.getY(0) + e.getY(1)) / 2f
                if (!twoFingerMoved && dist(twoFingerStartMidX, twoFingerStartMidY, midX, midY) > SLOP) {
                    twoFingerMoved = true
                }
                // 捏合检测
                if (settings.pinchZoom &&
                    abs(d - twoFingerStartDist) > PINCH_SLOP
                ) {
                    if (!pinchDetected) {
                        pinchDetected = true
                        // 双指捏合开始时结束滚动
                        endScroll()
                    }
                    val factor = (d / twoFingerStartDist.coerceAtLeast(1f))
                    onZoom?.invoke(factor, midX, midY)
                    twoFingerStartDist = d // 增量缩放
                    return true
                }
                if (pinchDetected) return true

                // 平移（已缩放状态下的双指拖动）或滚动
                val ddx = midX - twoFingerStartMidX
                val ddy = midY - twoFingerStartMidY
                if (onPan != null && panActive()) {
                    // 缩放态：双指拖动 = 平移
                    onPan?.invoke(midX - lastX, midY - lastY)
                } else {
                    // 滚动：流像素增量
                    val sdx = -(midX - twoFingerStartMidX - scrollAccumX) * pxScale
                    val sdy = -(midY - twoFingerStartMidY - scrollAccumY) * pxScale
                    if (fingerScrollAvailable) {
                        if (abs(sdx) >= 0.5f || abs(sdy) >= 0.5f) {
                            input.fingerScroll(sdx, sdy)
                            scrollAccumX = midX - twoFingerStartMidX
                            scrollAccumY = midY - twoFingerStartMidY
                        }
                    } else {
                        // 回退滚轮：累积换算为格数（~60 view px = 1 格）
                        scrollAccumY = midY - twoFingerStartMidY
                        scrollAccumX = midX - twoFingerStartMidX
                    }
                }
                lastX = midX; lastY = midY
            }

            else -> { /* NONE：忽略 */ }
        }
        return true
    }

    /** 外部查询当前是否处于本地缩放态（由 SessionActivity 提供实现） */
    @Volatile var isZoomed: () -> Boolean = { false }

    private fun panActive(): Boolean = isZoomed()

    private fun onPointerUp(e: MotionEvent): Boolean {
        if (e.pointerCount <= 2 && mode == Mode.TWO_FINGER) {
            endScroll()
            // 双指轻点判定
            val duration = System.currentTimeMillis() - twoFingerDownTime
            if (!twoFingerMoved && !pinchDetected && duration < TAP_TIMEOUT) {
                when (settings.twoFingerTap) {
                    "middle" -> clickAt(InputController.BUTTON_MIDDLE)
                    else -> clickAt(InputController.BUTTON_RIGHT)
                }
            }
            mode = Mode.NONE
        }
        return true
    }

    private fun onUp(e: MotionEvent): Boolean {
        cancelLongPress()
        val duration = System.currentTimeMillis() - downTime
        when (mode) {
            Mode.DRAG_LEFT, Mode.DRAG_RIGHT, Mode.DRAG_MIDDLE -> {
                if (settings.touchMode == "direct") {
                    input.mouseAbsolute(toStreamX(e.x), toStreamY(e.y), 0)
                } else {
                    // 触摸板模式长按拖拽结束：释放按键
                    input.mouseRelative(0, 0, 0)
                }
            }
            Mode.TRACKPAD_MOVE -> {
                if (!movedFar && duration < TAP_TIMEOUT) {
                    // 轻点 = 左键（trackpad 模式在当前位置点击）
                    clickAt(InputController.BUTTON_LEFT)
                }
                // 长按已触发 drag 的情形在 longPress 回调里处理
                else if (longPressFired && settings.longPress == "drag") {
                    // 拖拽结束：释放左键
                    input.mouseRelative(0, 0, 0)
                }
            }
            Mode.TWO_FINGER -> endScroll()
            else -> {}
        }
        mode = Mode.NONE
        return true
    }

    private fun onCancel(e: MotionEvent): Boolean {
        cancelLongPress()
        endScroll()
        // 释放全部按键，防卡键
        input.mouseAbsolute(toStreamX(e.x), toStreamY(e.y), 0)
        mode = Mode.NONE
        return true
    }

    private fun reset() {
        cancelLongPress()
        endScroll()
        try {
            input.mouseAbsolute(0, 0, 0)
        } catch (_: Exception) {
        }
        mode = Mode.NONE
    }

    // ------------------------------------------------------------------
    // 长按
    // ------------------------------------------------------------------

    private fun armLongPress() {
        cancelLongPress()
        val r = Runnable {
            longPressFired = true
            when (settings.longPress) {
                "middle" -> clickAt(InputController.BUTTON_MIDDLE)
                "drag" -> {
                    // 长按进入拖拽：按住左键（trackpad 模式下相对移动继续生效）
                    mode = Mode.DRAG_LEFT
                    if (settings.touchMode == "direct") {
                        input.mouseAbsolute(toStreamX(lastX), toStreamY(lastY), InputController.maskOf(InputController.BUTTON_LEFT))
                    } else {
                        input.mouseRelative(0, 0, InputController.maskOf(InputController.BUTTON_LEFT))
                    }
                }
                else -> clickAt(InputController.BUTTON_RIGHT)
            }
        }
        longPressRunnable = r
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(r, LONG_PRESS_TIMEOUT)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let {
            android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it)
        }
        longPressRunnable = null
    }

    /** 在当前虚拟位置点击指定按钮（按下 60ms 后释放的完整脉冲） */
    private fun clickAt(button: Int) {
        val mask = InputController.maskOf(button)
        val sx = curStreamX.toInt()
        val sy = curStreamY.toInt()
        input.mouseAbsolute(sx, sy, mask)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            input.mouseAbsolute(sx, sy, 0)
        }, 60)
    }

    /** 结束 sf 平滑滚动序列 */
    private fun endScroll() {
        if (fingerScrollAvailable) {
            try {
                input.fingerScrollEnd()
            } catch (_: Exception) {
            }
        } else {
            // 回退滚轮路径：把累积量换成格数发出
            val notchesY = (scrollAccumY / NOTCH_PX).toInt()
            val notchesX = (scrollAccumX / NOTCH_PX).toInt()
            if (notchesY != 0 || notchesX != 0) {
                input.scrollNotches(-notchesX, -notchesY)
            }
            scrollAccumY = 0f
            scrollAccumX = 0f
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun distance(e: MotionEvent, a: Int, b: Int): Float {
        val dx = e.getX(a) - e.getX(b)
        val dy = e.getY(a) - e.getY(b)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    companion object {
        private const val SLOP = 12f             // 移动判定阈值（view px）
        private const val PINCH_SLOP = 18f       // 捏合判定阈值（view px）
        private const val TAP_TIMEOUT = 220L     // 轻点时长上限 ms
        private const val LONG_PRESS_TIMEOUT = 450L
        private const val NOTCH_PX = 60f         // 回退滚轮：1 格 = 60 view px
    }
}
