package com.cshdotcom.selkies.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.cshdotcom.selkies.connection.SelkiesClient

/**
 * Android 手柄 → Selkies js 协议桥。
 * - 首个手柄事件到达时自动 js,c 连接（服务端呈现固定 Xbox 布局）
 * - 标准 16 键 / 6 轴映射（与浏览器 Gamepad API 标准一致）
 * - 按住期间 100ms js,h 心跳
 * - 触发键（LTRIGGER/RTRIGGER）作为按钮 6/7 发送
 * - 十字键（AXIS_HAT_*）转按钮 12..15
 * 服务端协议：js,c/d/b/a/h（对照 input_handler.py）
 */
class GamepadBridge(private val client: SelkiesClient) {

    private data class State(
        val buttons: FloatArray = FloatArray(17),
        val axes: FloatArray = FloatArray(6)
    )

    private val states = HashMap<Int, State>()
    private var lastHeartbeat = 0L
    private val slot = 0   // 单手柄使用 0 号槽（服务端 0..3）

    private fun connected(idx: Int, name: String) {
        client.gamepadConnect(slot, "Android:$name", 6, 17)
    }

    /** Activity 的 dispatchGenericMotionEvent / dispatchKeyEvent 转发到此 */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0 &&
            event.source and InputDevice.SOURCE_GAMEPAD == 0) return false
        val devId = event.deviceId
        val name = event.device?.name ?: "Gamepad"
        if (!states.containsKey(devId)) {
            states[devId] = State()
            connected(devId, name)
        }
        val st = states[devId] ?: return true
        // ---- 轴（-1..1） ----
        fun axis(a: Int): Float {
            val v = event.getAxisValue(a)
            return if (kotlin.math.abs(v) < 0.08f) 0f else v
        }
        updateAxis(devId, st, 0, axis(MotionEvent.AXIS_X))
        updateAxis(devId, st, 1, axis(MotionEvent.AXIS_Y))
        updateAxis(devId, st, 2, axis(MotionEvent.AXIS_Z))
        updateAxis(devId, st, 3, axis(MotionEvent.AXIS_RZ))
        // 触发键 → 按钮 6/7
        val lt = axis(MotionEvent.AXIS_LTRIGGER)
        val rt = axis(MotionEvent.AXIS_RTRIGGER)
        updateButton(devId, st, 6, lt)
        updateButton(devId, st, 7, rt)
        // 十字键 → 按钮 12..15
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        updateButton(devId, st, 12, if (hy < -0.5f) 1f else 0f)
        updateButton(devId, st, 13, if (hy > 0.5f) 1f else 0f)
        updateButton(devId, st, 14, if (hx < -0.5f) 1f else 0f)
        updateButton(devId, st, 15, if (hx > 0.5f) 1f else 0f)
        heartbeatIfNeeded()
        return true
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_BUTTON == 0 &&
            event.source and InputDevice.SOURCE_GAMEPAD == 0) return false
        val devId = event.deviceId
        val name = event.device?.name ?: "Gamepad"
        if (!states.containsKey(devId)) {
            states[devId] = State()
            connected(devId, name)
        }
        val btn = mapKeyCode(event.keyCode) ?: return false
        val st = states[devId] ?: return true
        val value = if (event.action == KeyEvent.ACTION_DOWN) 1f else 0f
        updateButton(devId, st, btn, value)
        heartbeatIfNeeded()
        return true
    }

    private fun heartbeatIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeat >= 100) {
            lastHeartbeat = now
            client.gamepadHeartbeat(slot)
        }
    }

    private fun updateButton(devId: Int, st: State, btn: Int, value: Float) {
        if (st.buttons.getOrNull(btn) == value) return
        st.buttons[btn] = value
        client.gamepadButton(slot, btn, value)
    }

    private fun updateAxis(devId: Int, st: State, axis: Int, value: Float) {
        if (st.axes.getOrNull(axis) == value) return
        st.axes[axis] = value
        client.gamepadAxis(slot, axis, value)
    }

    /** Android keyCode → 标准 Gamepad 按钮编号 */
    private fun mapKeyCode(kc: Int): Int? = when (kc) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        KeyEvent.KEYCODE_BUTTON_L2 -> 6
        KeyEvent.KEYCODE_BUTTON_R2 -> 7
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_START -> 9
        KeyEvent.KEYCODE_BUTTON_MODE -> 10
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 11
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 12
        KeyEvent.KEYCODE_DPAD_UP -> 13
        KeyEvent.KEYCODE_DPAD_DOWN -> 14
        KeyEvent.KEYCODE_DPAD_LEFT -> 15
        KeyEvent.KEYCODE_DPAD_RIGHT -> 16
        else -> null
    }

    fun release() {
        client.gamepadDisconnect(slot)
        states.clear()
    }
}
