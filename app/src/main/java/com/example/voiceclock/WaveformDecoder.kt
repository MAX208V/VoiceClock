package com.example.voiceclock

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * 音频波形解码器。
 *
 * 从音频文件中解码出 PCM 数据，计算每个分段的振幅峰值，
 * 归一化后作为波形图的柱状高度。
 *
 * 实现参考 SIY1121/WaveFormViewDemo（MIT License）的思路：
 * MediaExtractor 拆轨 → MediaCodec 解码 → 读取 PCM 求振幅。
 *
 * 无需任何权限；失败时返回空数组，由调用方回退到示意波形。
 */
object WaveformDecoder {

    private const val TAG = "WaveformDecoder"
    /** 最多解码的字节数，防止超长音频耗时。 */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** 从 URI 解码（系统铃声等）。 */
    fun decode(context: Context, uri: Uri, barCount: Int, sampleCount: Int): FloatArray {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val result = decodeFromExtractor(extractor, barCount, sampleCount)
            extractor.release()
            result
        } catch (error: Throwable) {
            Log.w(TAG, "解码 URI 波形失败: $uri", error)
            FloatArray(0)
        }
    }

    /** 从 raw 资源解码（内置提示音）。 */
    fun decodeResource(context: Context, resId: Int, barCount: Int, sampleCount: Int): FloatArray {
        return try {
            // 先复制到缓存目录，MediaExtractor 对 file descriptor 支持更稳
            val temp = File(context.cacheDir, "waveform_$resId.tmp")
            if (!temp.exists() || temp.length() == 0L) {
                context.resources.openRawResource(resId).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            decode(context, Uri.fromFile(temp), barCount, sampleCount)
        } catch (error: Throwable) {
            Log.w(TAG, "解码资源波形失败: $resId", error)
            FloatArray(0)
        }
    }

    private fun decodeFromExtractor(extractor: MediaExtractor, barCount: Int, sampleCount: Int): FloatArray {
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) return FloatArray(0)

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Throwable) {
            Log.w(TAG, "无法创建解码器 $mime", error)
            return FloatArray(0)
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalBytes = 0L

            // 收集所有振幅样本
            val samples = ArrayList<Float>(4096)
            var inputBufferIndex: Int
            var outputBufferIndex: Int

            while (!outputDone) {
                if (!inputDone) {
                    inputBufferIndex = codec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val buffer: ByteBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, size,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                outputBufferIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputBufferIndex >= 0) {
                    val outBuffer: ByteBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                    outBuffer.position(info.offset)
                    outBuffer.limit(info.offset + info.size)
                    if (info.size > 0) {
                        val shorts = ShortArray(info.size / 2)
                        outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        // 降采样：每若干采样取一个峰值
                        val step = max(1, shorts.size / sampleCount)
                        var i = 0
                        while (i < shorts.size) {
                            var peak = 0
                            val end = minOf(i + step, shorts.size)
                            for (j in i until end) {
                                val v = abs(shorts[j].toInt())
                                if (v > peak) peak = v
                            }
                            samples.add(peak / 32768f)
                            i += step
                        }
                        totalBytes += info.size
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (totalBytes > MAX_BYTES) outputDone = true
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 忽略，格式变化不影响
                }
            }

            if (samples.isEmpty()) return FloatArray(0)
            return downSample(samples, barCount)
        } catch (error: Throwable) {
            Log.w(TAG, "解码过程失败", error)
            return FloatArray(0)
        } finally {
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            try {
                codec.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** 把样本序列分成 barCount 段，每段取峰值并归一化到 0f..1f。 */
    private fun downSample(samples: List<Float>, barCount: Int): FloatArray {
        val result = FloatArray(barCount) { 0f }
        if (samples.isEmpty()) return result
        val perBar = max(1, samples.size / barCount)
        var globalMax = 0f
        for (b in 0 until barCount) {
            val start = b * perBar
            val end = minOf(start + perBar, samples.size)
            if (start >= samples.size) break
            var peak = 0f
            for (i in start until end) {
                if (samples[i] > peak) peak = samples[i]
            }
            result[b] = peak
            if (peak > globalMax) globalMax = peak
        }
        // 归一化，并保证最小可见高度
        if (globalMax > 0.0001f) {
            for (b in 0 until barCount) {
                result[b] = (result[b] / globalMax).coerceIn(0.05f, 1f)
            }
        }
        return result
    }
}
