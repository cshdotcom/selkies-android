package com.selkies.client.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.selkies.client.net.ConnectionEngine
import com.selkies.client.net.Protocol

/**
 * 摄像头上行 —— 官方 websockets 模式 0x06 链路（手机摄像头 → 远端虚拟摄像头）。
 *
 * 帧格式（对照 webcam.py / selkies-ws-core.js startWebcamCapture）：
 *  [0x06][codec][flags][payload]
 *  - codec: 0=MJPEG 1=H264 2=VP8 3=VP9 4=AV1 5=HEVC —— 本端固定 H264(1)；
 *  - flags bit0 = 关键帧；bits1-2 = 顺时针旋转 1/4 圈数；bit3 = 水平翻转。
 *    （旋转/翻转不烘焙进码流，由服务器按 flags 处理 —— 对齐官方语义）
 *  - 服务器收 WEBCAM_KEYFRAME 请求 → 立即编出关键帧（requestSyncKeyframe）。
 *
 * 管线：Camera2（后置优先）→ MediaCodec Surface 输入（零拷贝）→ H264 → WS。
 */
class WebcamUplink(
    private val context: Context,
    private val engine: ConnectionEngine,
    private val onError: (String) -> Unit
) {

    @Volatile private var running = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var drainThread: Thread? = null

    /** 传感器方向 → 顺时针 1/4 圈数（flags bits1-2） */
    private var rotationQuarters = 0
    private var frontCamera = false

    val isRunning: Boolean get() = running

    companion object {
        private const val TAG = "WebcamUplink"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 24
        const val BITRATE = 2_500_000
        const val CODEC_H264_ID = 1
    }

    @SuppressLint("MissingPermission")
    fun start(useFront: Boolean = false): Boolean {
        if (running) return true
        frontCamera = useFront
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = pickCamera(cm, useFront)
        if (cameraId == null) {
            onError("no camera found")
            return false
        }

        return try {
            val chars = cm.getCameraCharacteristics(cameraId)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            // 传感器方向 → 顺时针旋转 1/4 圈（90°=1, 180°=2, 270°=3）
            rotationQuarters = ((sensorOrientation % 360) / 90) and 0x03

            cameraThread = HandlerThread("webcam-cam").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)

            // 编码器：Surface 输入（Camera2 直接渲染进编码器，零拷贝）
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC)
                setInteger(MediaFormat.KEY_WIDTH, WIDTH)
                setInteger(MediaFormat.KEY_HEIGHT, HEIGHT)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
            codec = c

            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    try {
                        startSession(cam)
                    } catch (e: Exception) {
                        Log.e(TAG, "camera session failed", e)
                        onError(e.message ?: "camera session failed")
                        stop()
                    }
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    if (camera == cam) camera = null
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    onError("camera error $error")
                    stop()
                }
            }, cameraHandler)

            running = true
            drainThread = Thread { drainLoop() }.apply { name = "webcam-drain"; start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stop()
            onError(e.message ?: "webcam start failed")
            false
        }
    }

    fun stop() {
        running = false
        try {
            session?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            camera?.close()
        } catch (_: Exception) {
        }
        camera = null
        try {
            codec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            drainThread?.join(500)
        } catch (_: Exception) {
        }
        drainThread = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    /** 服务器 WEBCAM_KEYFRAME → 请求立即输出关键帧 */
    fun requestKeyframe() {
        val c = codec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyframe: ${e.message}")
        }
    }

    // ------------------------------------------------------------------

    private fun pickCamera(cm: CameraManager, front: Boolean): String? {
        var firstAny: String? = null
        for (id in cm.cameraIdList) {
            val ch = try {
                cm.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue
            }
            if (ch.get(CameraCharacteristics.LENS_FACING) ==
                if (front) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK
            ) return id
            if (firstAny == null) firstAny = id
        }
        return firstAny
    }

    private fun startSession(cam: CameraDevice) {
        val surface = inputSurface ?: return
        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(surface)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        try {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                android.util.Range(FPS, FPS))
        } catch (_: Exception) {
        }

        cam.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(builder.build(), null, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "repeating request failed", e)
                        onError(e.message ?: "camera repeat failed")
                        stop()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("camera configure failed")
                    stop()
                }
            },
            cameraHandler
        )
    }

    /** 编码输出 → 0x06 帧发送循环 */
    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running && !Thread.currentThread().isInterrupted) {
            val c = codec ?: break
            try {
                var idx = c.dequeueOutputBuffer(info, 10_000)
                while (idx >= 0) {
                    if (info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val buf = c.getOutputBuffer(idx)!!
                        val payload = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(payload)
                        if (engine.canSendUplink() && payload.isNotEmpty()) {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            sendFrame(key, payload)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                    idx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "drain: ${e.message}")
            }
        }
    }

    private fun sendFrame(keyframe: Boolean, payload: ByteArray) {
        // [0x06][codec][flags][payload]；flags: bit0 key, bits1-2 rot, bit3 hflip（前置镜像）
        var flags = 0
        if (keyframe) flags = flags or 0x01
        flags = flags or ((rotationQuarters and 0x03) shl 1)
        if (frontCamera) flags = flags or 0x08
        val frame = ByteArray(3 + payload.size)
        frame[0] = Protocol.BINARY_WEBCAM.toByte()
        frame[1] = CODEC_H264_ID.toByte()
        frame[2] = flags.toByte()
        System.arraycopy(payload, 0, frame, 3, payload.size)
        engine.sendBinary(frame)
    }
}
