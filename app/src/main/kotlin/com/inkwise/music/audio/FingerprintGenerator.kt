package com.inkwise.music.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

class FingerprintGenerator {

    companion object {
        private const val TAG = "FingerprintGenerator"
        private const val TIMEOUT_US = 10000L

        init {
            System.loadLibrary("fingerprint_jni")
        }
    }

    data class FingerprintResult(
        val fingerprint: String,
        val duration: Double
    )

    fun generate(filePath: String): FingerprintResult? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var ctxPtr = 0L
        var ctxCreated = false

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)

            val trackIndex = findAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val duration = durationUs / 1_000_000.0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Wait for the actual output format before creating Chromaprint context
            var actualSampleRate = sampleRate
            var actualChannelCount = channelCount

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputBufIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputBufIdx >= 0 -> {
                        if (!ctxCreated) {
                            val outputFormat = codec.outputFormat
                            if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                actualSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                actualChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            Log.d(TAG, "Output format: sampleRate=$actualSampleRate, channels=$actualChannelCount (input was $sampleRate/$channelCount)")
                            ctxPtr = nativeCreate(actualSampleRate, actualChannelCount)
                            if (ctxPtr == 0L) {
                                Log.e(TAG, "Failed to create Chromaprint context")
                                return null
                            }
                            ctxCreated = true
                        }
                        val outputBuf = codec.getOutputBuffer(outputBufIdx)!!
                        if (bufferInfo.size > 0) {
                            processPcmBuffer(outputBuf, bufferInfo, ctxPtr)
                        }
                        codec.releaseOutputBuffer(outputBufIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (ctxCreated) {
                            // Output format changed mid-stream — unexpected but handle it
                            val outputFormat = codec.outputFormat
                            actualSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, actualSampleRate)
                            actualChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, actualChannelCount)
                        }
                    }
                }
            }

            val fingerprint = nativeFinish(ctxPtr)
            return if (fingerprint != null) {
                FingerprintResult(fingerprint, duration)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating fingerprint for $filePath: ${e.message}", e)
            return null
        } finally {
            if (ctxPtr != 0L) nativeFree(ctxPtr)
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return null
    }

    private fun processPcmBuffer(
        outputBuf: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        ctxPtr: Long
    ) {
        val shortBuf = outputBuf.asShortBuffer()
        val shortArray = ShortArray(bufferInfo.size / 2)
        val pos = shortBuf.position()
        shortBuf.position(bufferInfo.offset / 2)
        shortBuf.get(shortArray, 0, shortArray.size)
        shortBuf.position(pos)
        nativeFeed(ctxPtr, shortArray, shortArray.size)
    }

    private external fun nativeCreate(sampleRate: Int, numChannels: Int): Long
    private external fun nativeFeed(contextPtr: Long, pcmData: ShortArray, size: Int): Boolean
    private external fun nativeFinish(contextPtr: Long): String?
    private external fun nativeFree(contextPtr: Long)

    /**
     * Convert a base64 compressed fingerprint to raw comma-separated uint32 format
     * for the server API.
     */
    fun base64ToRaw(base64Fp: String): String? {
        return nativeBase64ToRaw(base64Fp)
    }

    private external fun nativeBase64ToRaw(base64Fp: String): String?
}
