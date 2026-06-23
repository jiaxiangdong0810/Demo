package com.example.myapplication.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.roundToInt

class Mp3FileDecoder(
    private val targetSampleRate: Int = 16000
) {
    fun decode(file: File): PcmAudio {
        if (!file.exists() || file.length() == 0L) {
            return PcmAudio(FloatArray(0), targetSampleRate)
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                return PcmAudio(FloatArray(0), targetSampleRate)
            }

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return PcmAudio(FloatArray(0), targetSampleRate)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val decodedSamples = ArrayList<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            var outputSampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, targetSampleRate)
            var outputChannels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = inputBuffer?.let {
                            it.clear()
                            extractor.readSampleData(it, 0)
                        } ?: -1

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        outputSampleRate = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE,
                            outputSampleRate
                        )
                        outputChannels = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            outputChannels
                        )
                    }
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                appendPcm16Samples(outputBuffer, outputChannels, decodedSamples)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEnd = true
                            }
                        }
                    }
                }
            }

            val monoSamples = decodedSamples.toFloatArray()
            val normalized = if (outputSampleRate == targetSampleRate) {
                monoSamples
            } else {
                resampleLinear(monoSamples, outputSampleRate, targetSampleRate)
            }
            return PcmAudio(normalized, targetSampleRate)
        } finally {
            runCatching {
                codec?.stop()
            }
            runCatching {
                codec?.release()
            }
            runCatching {
                extractor.release()
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                return index
            }
        }
        return -1
    }

    private fun appendPcm16Samples(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        output: MutableList<Float>
    ) {
        val safeChannels = channels.coerceAtLeast(1)
        while (buffer.remaining() >= BYTES_PER_SAMPLE * safeChannels) {
            var frameSum = 0f
            repeat(safeChannels) {
                frameSum += buffer.short / 32768f
            }
            output.add(frameSum / safeChannels)
        }
    }

    private fun resampleLinear(
        input: FloatArray,
        sourceSampleRate: Int,
        targetSampleRate: Int
    ): FloatArray {
        if (input.isEmpty() || sourceSampleRate <= 0 || sourceSampleRate == targetSampleRate) {
            return input
        }

        val outputSize = (input.size.toDouble() * targetSampleRate / sourceSampleRate).roundToInt()
            .coerceAtLeast(1)
        val output = FloatArray(outputSize)
        val ratio = sourceSampleRate.toDouble() / targetSampleRate

        for (i in output.indices) {
            val sourceIndex = i * ratio
            val leftIndex = sourceIndex.toInt().coerceIn(0, input.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourceIndex - leftIndex).toFloat()
            output[i] = input[leftIndex] * (1f - fraction) + input[rightIndex] * fraction
        }

        return output
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val BYTES_PER_SAMPLE = 2
    }
}
