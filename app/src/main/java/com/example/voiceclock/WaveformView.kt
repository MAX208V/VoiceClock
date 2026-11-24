package com.example.voiceclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
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
        /** 拖动标记的触摸容差（像素）。 */
        private const val TOUCH_SLOP_DP = 24f
        /** 最短可播放时长（毫秒），避免误触变成 0。 */
        const val MIN_PLAY_MS = 500L
    }

    /** 用户拖选时长后的回调（毫秒，等于总时长表示完整播放）。 */
    var onCutChanged: ((Long, Long) -> Unit)? = null

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

    /** 是否正在拖动结束标记。 */
    private var dragging = false

    /** 刻度与提示文字画笔。 */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        textSize = 24f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE57373.toInt()
    }

    /** 可交互模式：显示拖动标记并响应触摸。设置为 false 时仅做展示。 */
    var interactive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        // 默认给一条平缓的示意波形，避免空白
        amplitudes = FloatArray(BAR_COUNT) { i ->
            (0.25f + 0.2f * kotlin.math.sin(i * 0.7f)).coerceIn(0.1f, 0.6f)
        }
    }

    /**
     * 设置截断位置（0f..1f）。
     * 传 1f 表示完整播放。
     */
    fun setCutRatio(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(clamped - cutRatio) > 0.005f) {
            cutRatio = clamped
            invalidate()
        }
    }

    /** 当前选中的播放时长（毫秒）。总时长未知时返回 0。 */
    fun selectedPlayMs(): Long {
        val total = audioDurationMs
        if (total <= 0L) return 0L
        return (total * cutRatio).toLong().coerceIn(MIN_PLAY_MS, total)
    }

    /**
     * 按时长（毫秒）设置选中位置。
     * 传入 <= 0 或 >= 总时长表示完整播放。
     */
    fun setSelectedPlayMs(ms: Long) {
        val total = audioDurationMs
        if (total <= 0L) {
            setCutRatio(1f)
            return
        }
        val clamped = when {
            ms <= 0L -> total
            ms >= total -> total
            else -> ms.coerceAtLeast(MIN_PLAY_MS)
        }
        setCutRatio(clamped.toFloat() / total.toFloat())
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

        // 为底部刻度留出空间
        val labelHeight = if (interactive) labelPaint.textSize * 1.6f else 0f
        val waveHeight = h - labelHeight
        val centerY = waveHeight / 2f
        val maxBarHeight = waveHeight * 0.9f

        val gap = w / (count * 4f)
        val barWidth = (w - gap * (count - 1)) / count

        // 未选中区域整体压暗，突出选中范围
        val dimPaint = idlePaint
        for (i in 0 until count) {
            val amp = amplitudes[i].coerceIn(0.02f, 1f)
            val barHeight = amp * maxBarHeight
            val left = i * (barWidth + gap)
            val right = left + barWidth
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f

            val barCenterRatio = (i + 0.5f) / count
            val paint = when {
                barCenterRatio > cutRatio -> dimPaint      // 选中之外的区域
                progress > 0f && barCenterRatio <= progress -> playedPaint  // 正在播放
                else -> barPaint                            // 选中范围内
            }
            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, paint)
        }

        // 结束标记线 + 把手
        if (cutRatio < 0.999f || interactive) {
            val cutX = (w * cutRatio).coerceIn(0f, w)
            canvas.drawLine(cutX, 0f, cutX, waveHeight, cutPaint)
            if (interactive) {
                // 圆形把手，提示可拖动
                val handleRadius = 10f
                canvas.drawCircle(cutX, waveHeight / 2f, handleRadius, handlePaint)
            }
        }

        // 底部刻度：0s / 选中时长 / 总时长
        if (interactive && audioDurationMs > 0L) {
            val total = audioDurationMs
            val selected = selectedPlayMs()
            val baseline = h - labelPaint.textSize * 0.3f
            canvas.drawText("0s", 4f, baseline, labelPaint)
            val rightText = String.format(Locale.US, "%.1fs", total / 1000f)
            val rightWidth = labelPaint.measureText(rightText)
            canvas.drawText(rightText, w - rightWidth - 4f, baseline, labelPaint)
            if (selected < total) {
                val midText = String.format(Locale.US, "%.1fs", selected / 1000f)
                val midWidth = labelPaint.measureText(midText)
                val midX = (w * cutRatio - midWidth / 2f).coerceIn(0f, w - midWidth)
                canvas.drawText(midText, midX, baseline, labelPaint)
            }
        }
    }

    // ---------------- 触摸交互 ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || audioDurationMs <= 0L) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只在靠近标记或任意位置按下时开始拖动
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateCutFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateCutFromTouch(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    updateCutFromTouch(event.x)
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // 松手后回报最终选择
                    emitCutChanged()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** 根据触摸 X 坐标更新选中位置。点最右侧即完整播放。 */
    private fun updateCutFromTouch(touchX: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        val ratio = (touchX / w).coerceIn(0f, 1f)
        val total = audioDurationMs
        if (total <= 0L) return

        val ms = (total * ratio).toLong()
        // 靠近最右端时吸附为完整播放
        val snapped = if (ratio > 0.97f) total else ms.coerceAtLeast(MIN_PLAY_MS)
        setCutRatio(snapped.toFloat() / total.toFloat())
        emitCutChanged()
    }

    private fun emitCutChanged() {
        val total = audioDurationMs
        if (total <= 0L) return
        onCutChanged?.invoke(selectedPlayMs(), total)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        executor.shutdownNow()
    }
}
