package com.example.myapplication.ble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

/**
 * 环形声波可视化 View
 *
 * 在圆周上绘制若干竖条，高度随音频音量变化。
 * 空闲时竖条较矮且缓慢旋转，录音时竖条变高且跟随音量跳动。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 48
        private const val BAR_WIDTH_DP = 3f
        private const val BAR_MIN_HEIGHT_DP = 6f
        private const val BAR_MAX_HEIGHT_DP = 48f
        private const val RADIUS_RATIO = 0.32f
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1D4ED8.toInt()  // 更深的蓝色
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private val barWidthPx = BAR_WIDTH_DP * resources.displayMetrics.density
    private val barMinHeightPx = BAR_MIN_HEIGHT_DP * resources.displayMetrics.density
    private val barMaxHeightPx = BAR_MAX_HEIGHT_DP * resources.displayMetrics.density

    private var rotationAngle = 0f
    private var targetLevels = FloatArray(BAR_COUNT)
    private var currentLevels = FloatArray(BAR_COUNT)
    private var isRecording = false

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 20_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    fun startRecording() {
        isRecording = true
        barPaint.color = 0xFF2563EB.toInt()  // 录音时用亮蓝色
        rotationAnimator.duration = 12_000L
        if (!rotationAnimator.isRunning) rotationAnimator.start()
    }

    fun stopRecording() {
        isRecording = false
        barPaint.color = 0xFF1D4ED8.toInt()  // 停止时用深蓝色
        rotationAnimator.duration = 20_000L
        targetLevels.fill(0f)
    }

    /**
     * 更新音量级别
     * @param level 0.0 ~ 1.0
     */
    fun updateLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        for (i in 0 until BAR_COUNT) {
            val phase = (i.toFloat() / BAR_COUNT) * PI.toFloat() * 4f
            val variation = 0.4f + 0.6f * sin(phase + rotationAngle * 0.08f)
            targetLevels[i] = clamped * variation
        }
        if (!rotationAnimator.isRunning) invalidate()
    }

    fun release() {
        rotationAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = cx * RADIUS_RATIO

        // 平滑过渡
        for (i in 0 until BAR_COUNT) {
            currentLevels[i] += (targetLevels[i] - currentLevels[i]) * 0.25f
        }

        for (i in 0 until BAR_COUNT) {
            val angle = (i.toFloat() / BAR_COUNT) * 360f + rotationAngle
            val radians = Math.toRadians(angle.toDouble())

            val level = currentLevels[i].coerceIn(0f, 1f)
            val barHeight = barMinHeightPx + (barMaxHeightPx - barMinHeightPx) * level

            val innerX = cx + cos(radians).toFloat() * radius
            val innerY = cy + sin(radians).toFloat() * radius
            val outerX = cx + cos(radians).toFloat() * (radius + barHeight)
            val outerY = cy + sin(radians).toFloat() * (radius + barHeight)

            // 始终保持高不透明度
            barPaint.alpha = if (isRecording) {
                (200 + 55 * level).toInt().coerceIn(180, 255)
            } else {
                (100 + 60 * level).toInt().coerceIn(80, 160)
            }

            canvas.drawLine(innerX, innerY, outerX, outerY, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
