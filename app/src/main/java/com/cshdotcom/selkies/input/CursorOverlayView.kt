package com.cshdotcom.selkies.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 光标覆盖层：绘制服务端下发的光标 PNG（cursor,{json}），
 * 位置由本端最后发送的指针坐标经 (scale, panX, panY) 正变换得到。
 */
class CursorOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bmp: Bitmap? = null
    private var hotX = 0
    private var hotY = 0
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    /** 远端坐标下的指针位置（随 m 消息更新） */
    @Volatile var remoteX = -1
    @Volatile var remoteY = -1

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setCursor(bitmap: Bitmap?, hotX: Int, hotY: Int) {
        this.bmp = bitmap
        this.hotX = hotX
        this.hotY = hotY
        postInvalidate()
    }

    fun setTransform(scale: Float, panX: Float, panY: Float) {
        this.scale = scale; this.panX = panX; this.panY = panY
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        if (remoteX < 0 || remoteY < 0) return
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate(panX, panY)
        canvas.setMatrix(m)
        // 热点对齐
        canvas.drawBitmap(b, (remoteX - hotX).toFloat(), (remoteY - hotY).toFloat(), paint)
        canvas.setMatrix(null)
    }
}
