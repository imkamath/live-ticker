package com.liveticker.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil

/** Smooth, endless scrolling text that keeps its position when the text updates. */
class MarqueeView(context: Context) : View(context) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var text: CharSequence = ""
    private var layout: StaticLayout? = null
    private var textWidth = 0f
    private var offset = 0f
    private var lastFrame = 0L
    var speedDpPerSec = 60f

    fun setTextSizeSp(sp: Float) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        rebuild()
        requestLayout()
    }

    fun setText(t: CharSequence) {
        val oldHeight = layout?.height
        text = t
        rebuild()
        if (layout?.height != oldHeight) requestLayout()
        invalidate()
    }

    private fun rebuild() {
        val w = ceil(Layout.getDesiredWidth(text, paint)).toInt().coerceAtLeast(1)
        layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setIncludePad(false)
            .setMaxLines(1)
            .build()
        textWidth = w.toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetricsInt
        val h = (layout?.height ?: (fm.descent - fm.ascent)) + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        val avail = width - paddingLeft - paddingRight
        canvas.save()
        canvas.clipRect(paddingLeft, 0, width - paddingRight, height)

        if (textWidth <= avail) {               // fits: no scrolling needed
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            l.draw(canvas)
            canvas.restore()
            lastFrame = 0L
            return
        }

        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L) {
            val dt = (now - lastFrame).coerceAtMost(100L)
            offset -= speedDpPerSec * resources.displayMetrics.density * dt / 1000f
        }
        lastFrame = now

        val cycle = textWidth + paint.textSize * 4
        while (offset <= -cycle) offset += cycle
        var x = paddingLeft + offset
        while (x < width) {
            canvas.save()
            canvas.translate(x, paddingTop.toFloat())
            l.draw(canvas)
            canvas.restore()
            x += cycle
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lastFrame = 0L
    }
}
