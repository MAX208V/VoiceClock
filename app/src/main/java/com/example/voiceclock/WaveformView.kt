package com.example.voiceclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.Executors

/**
 * 音频波形可视化控件。
 *
 * 实现思路参考开源项目 SIY1121/WaveFormViewDemo（MIT）：
 * 解码音频得到振幅数据，用 Canvas 绘制柱状波形。
 *
 * 与 Visualizer API 方案的区别：
 * Visualizer 需要 RECORD_AUDIO 录音权限，且部分机型不支持；
 * 本控件直接解码音频文件/资源，**无需任何权限**，兼容性更好。
 *
 * 使用：
 *   waveformView.load(Uri.parse("content://..."))   // 系统铃声
 *   waveformView.loadResource(R.raw.time_chime)     // 内置提示音
 *   waveformView.setProgress(0.35f)                 // 播放进度 0f..1f
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 波形柱数量。 */
        private const val BAR_COUNT = 48
        /** 解码时的采样点数。 */
        private const val SAMPLE_COUNT = 512
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF4A90D9.toInt()
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFCCCCCC.toInt()
    }

    /** 振幅数据，范围 0f..1f。 */
    private var amplitudes: FloatArray = FloatArray(BAR_COUNT) { 0f }

    /** 播放进度 0f..1f。 */
    private var progress: Float = 0f

    /** 截断位置（0f..1f，1f 表示完整播放），用于标示实际播放范围。 */
    private var cutRatio: Float = 1f

    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFE57373.toInt()
    }

    private var loading = false

    private val executor = Executors.newSingleThreadExecutor()

    private val path = Path()

    init {
        // 默认给一条平缓的示意波形，避免空白
        amplitudes = FloatArray(BAR_COUNT) { i ->
            (0.25f + 0.2f * kotlin.math.sin(i * 0.7f)).coerceIn(0.1f, 0.6f)
        }
    }

    /**
     * 设置截断位置（0f..1f）。
     * 只在设置了提示音播放长度时生效，用来标示「只会播到这里」。
     * 传 1f 表示完整播放，不绘制截断线。
     */
    fun setCutRatio(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(clamped - cutRatio) > 0.005f) {
            cutRatio = clamped
            invalidate()
        }
    }

    /** 设置播放进度（0f..1f），用于高亮已播放部分。 */
    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(clamped - progress) > 0.005f) {
            progress = clamped
            invalidate()
        }
    }

    /** 音频总时长（毫秒），0 表示未知。 */
    var audioDurationMs: Long = 0L
        private set

    /** 从 URI 加载波形（系统铃声）。 */
    fun load(uri: Uri) {
        if (loading) return
        loading = true
        executor.execute {
            val result = try {
                WaveformDecoder.decode(context, uri, BAR_COUNT, SAMPLE_COUNT)
            } catch (_: Throwable) {
                WaveformDecoder.Result(FloatArray(0), 0L)
            }
            post {
                audioDurationMs = result.durationMs
                applyAmplitudes(result.amplitudes)
                loading = false
            }
        }
    }

    /** 从 raw 资源加载波形（内置提示音）。 */
    fun loadResource(resId: Int) {
        if (loading) return
        loading = true
        executor.execute {
            val result = try {
                WaveformDecoder.decodeResource(context, resId, BAR_COUNT, SAMPLE_COUNT)
            } catch (_: Throwable) {
                WaveformDecoder.Result(FloatArray(0), 0L)
            }
            post {
                audioDurationMs = result.durationMs
                applyAmplitudes(result.amplitudes)
                loading = false
            }
        }
    }

    /** 清空为默认示意波形。 */
    fun reset() {
        amplitudes = FloatArray(BAR_COUNT) { i ->
            (0.25f + 0.2f * kotlin.math.sin(i * 0.7f)).coerceIn(0.1f, 0.6f)
        }
        progress = 0f
        cutRatio = 1f
        audioDurationMs = 0L
        invalidate()
    }

    private fun applyAmplitudes(raw: FloatArray) {
        amplitudes = if (raw.isEmpty()) {
            // 解码失败时回退示意波形
            FloatArray(BAR_COUNT) { i ->
                (0.25f + 0.2f * kotlin.math.sin(i * 0.7f)).coerceIn(0.1f, 0.6f)
            }
        } else {
            raw
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val count = amplitudes.size
        if (count == 0) return

        val gap = w / (count * 4f)
        val barWidth = (w - gap * (count - 1)) / count
        val centerY = h / 2f
        val maxBarHeight = h * 0.9f

        for (i in 0 until count) {
            val amp = amplitudes[i].coerceIn(0.02f, 1f)
            val barHeight = amp * maxBarHeight
            val left = i * (barWidth + gap)
            val right = left + barWidth
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f

            val barProgress = (i + 1f) / count
            val paint = when {
                barProgress <= progress -> playedPaint
                progress > 0f -> idlePaint
                else -> barPaint
            }
            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, paint)
        }

        // 截断线：标示实际会播放到哪里
        if (cutRatio < 0.999f) {
            val cutX = w * cutRatio
            canvas.drawLine(cutX, 0f, cutX, h, cutPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        executor.shutdownNow()
    }
}
