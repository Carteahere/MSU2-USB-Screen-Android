package com.msu2.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

// 网速曲线颜色预览自定义视图
class NetColorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 获取当前设置的文本及上下行线条颜色
    var colorsProvider: (() -> Triple<Int, Int, Int>)? = null

    private val density = resources.displayMetrics.density
    private val plot = ArrayDeque<Pair<Double, Double>>()
    private var lastTime = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var sent = 0.0
    private var recv = 0.0
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            invalidate()
            postDelayed(this, 1000L)
        }
    }

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.GRAY
        alpha = 110
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var uploadColor = Color.WHITE
    private var downloadColor = Color.WHITE

    init {
        repeat(80) { plot.addLast(0.0 to 0.0) }
        val (rx, tx) = StatusProvider.netCounters()
        lastRx = rx
        lastTx = tx
        lastTime = SystemClock.elapsedRealtime()
    }

    // 更新界面色彩配置并重绘视图
    fun refresh() {
        colorsProvider?.invoke()?.let { (t, u, d) ->
            textPaint.color = t
            uploadColor = u
            downloadColor = d
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val targetWidth: Int
        val targetHeight: Int

        if (widthMode == MeasureSpec.EXACTLY || (widthMode == MeasureSpec.AT_MOST && widthSize > 0)) {
            targetWidth = widthSize
            targetHeight = (targetWidth / 2f).toInt()
        } else if (heightMode == MeasureSpec.EXACTLY || (heightMode == MeasureSpec.AT_MOST && heightSize > 0)) {
            targetHeight = heightSize
            targetWidth = targetHeight * 2
        } else {
            targetHeight = (80 * density).toInt()
            targetWidth = targetHeight * 2
        }

        setMeasuredDimension(targetWidth, targetHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = 4 * density
        val rect = RectF(0f, 0f, w, h)
        val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, bgPaint)

        // 绘制速率文字标识
        textPaint.textSize = 14f * (h / 80f)
        val pad = 6f * (w / 160f)
        canvas.drawText("上传  ${formatSpeed(sent).padStart(8)}", pad, 16f * (h / 80f), textPaint)
        canvas.drawText("下载  ${formatSpeed(recv).padStart(8)}", pad, 50f * (h / 80f), textPaint)

        // 绘制上下行波形曲线
        drawBand(canvas, plot.map { it.first }, 36f * (h / 80f), 17f * (h / 80f), uploadColor)
        drawBand(canvas, plot.map { it.second }, 72f * (h / 80f), 17f * (h / 80f), downloadColor)
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, borderPaint)
    }

    // 采集网络传输速率
    private fun sample() {
        val (rx, tx) = StatusProvider.netCounters()
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTime) / 1000.0
        sent = if (tx >= 0 && tx >= lastTx && dt > 0) (tx - lastTx) / dt else 0.0
        recv = if (rx >= 0 && rx >= lastRx && dt > 0) (rx - lastRx) / dt else 0.0
        lastTime = now
        lastRx = rx
        lastTx = tx
        if (plot.isNotEmpty()) plot.removeFirst()
        plot.addLast(sent to recv)
    }

    // 绘制速率波形填充渐变区域
    private fun drawBand(canvas: Canvas, values: List<Double>, baseY: Float, amp: Float, color: Int) {
        val recent = values.takeLast(80)
        if (recent.isEmpty()) return
        val maxValue = recent.maxOrNull() ?: 0.0
        linePaint.color = color
        fillPaint.color = color
        fillPaint.alpha = 60
        val line = Path()
        val fill = Path()
        val step = if (recent.size <= 1) width.toFloat() else width.toFloat() / (recent.size - 1)
        recent.forEachIndexed { i, v ->
            val x = i * step
            val y = baseY - (if (maxValue > 0) (v / maxValue * amp).toFloat() else 0f)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, baseY)
            }
            line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(width.toFloat(), baseY)
        fill.close()
        canvas.save()
        canvas.clipRect(0f, baseY - amp, width.toFloat(), baseY)
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
        canvas.restore()
    }

    private fun formatSpeed(num: Double): String {
        val kb = num / 1024.0
        val mb = kb / 1024.0
        return when {
            abs(mb) >= 100.0 -> String.format("%.0fMB/s", mb)
            abs(mb) >= 1.0 -> String.format("%.1fMB/s", mb)
            abs(kb) >= 100.0 -> String.format("%.0fKB/s", kb)
            else -> String.format("%.1fKB/s", kb)
        }
    }
}
