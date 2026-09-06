/*
 * FingerprintGenerator.kt
 *
 * 音频指纹生成器：用 MediaExtractor + MediaCodec 把任意音频文件解码为 PCM，
 * 逐块喂给 Chromaprint（通过 JNI 封装的 fingerprint_jni）生成声学指纹。
 * 生成的指纹用于离线歌曲识别/匹配（与服务器 API 交互）。
 *
 * 线程模型：generate() 为阻塞式长任务（解码整首音频），调用方须在
 * IO 协程中调用，避免阻塞主线程。
 *
 * 资源释放：所有 MediaExtractor / MediaCodec 与 native Chromaprint 上下文
 * 均在 finally 中释放，保证解码中断或失败时也不泄漏。
 */
package com.inkwise.music.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * 音频指纹生成器。
 *
 * 使用 Android 系统解码器把音频解码为 PCM 短整型样本，分批送入
 * native Chromaprint 上下文；最终产出的指纹为 Base64 字符串，
 * 同时附带音频时长（秒）。base64ToRaw 可把指纹转成服务端 API 所需的
 * 逗号分隔 uint32 原始格式。
 *
 * 注意：加载 fingerprint_jni 库在伴生对象 init 中完成，类首次触及时执行。
 */
class FingerprintGenerator {

    companion object {
        private const val TAG = "FingerprintGenerator"
        /** MediaCodec 输入/输出缓冲的最大等待时长（微秒） */
        private const val TIMEOUT_US = 10000L

        init {
            System.loadLibrary("fingerprint_jni")
        }
    }

    /** 指纹生成结果：Base64 指纹串 + 音频时长（秒） */
    data class FingerprintResult(
        val fingerprint: String,
        val duration: Double
    )

    /**
     * 为音频文件生成指纹（阻塞式，须在 IO 线程调用）。
     *
     * 流程：定位音频轨道 → 创建解码器 → 一边喂输入一边收输出，
     * 在收到首个输出缓冲时按实际输出格式创建 Chromaprint 上下文，
     * 每块 PCM 经 [processPcmBuffer] 送入 native → 播放结束 nativeFinish
     * 产出指纹。任一步失败或文件无音频轨时返回 null，并释放全部资源。
     */
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

    /** 从提取器中找到第一个 audio/ 类型的轨道索引；没有音频轨返回 null */
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

    /**
     * 把解码器输出的一块 PCM 数据转成 ShortArray 送入 native Chromaprint。
     * 输出缓冲按 2 字节小端（native order）视为 short 序列；
     * 只取 bufferInfo 声明的有效区间，随后恢复 ByteBuffer 位置。
     */
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

    /** JNI：以给定采样率与声道数创建 Chromaprint 上下文；返回 native 指针（0 表示失败） */
    private external fun nativeCreate(sampleRate: Int, numChannels: Int): Long

    /** JNI：把一帧 PCM 送入 Chromaprint 上下文；返回是否成功 */
    private external fun nativeFeed(contextPtr: Long, pcmData: ShortArray, size: Int): Boolean

    /** JNI：解码完毕，产出 Base64 指纹；返回 null 表示指纹无效 */
    private external fun nativeFinish(contextPtr: Long): String?

    /** JNI：释放 Chromaprint 上下文，防止 native 内存泄漏 */
    private external fun nativeFree(contextPtr: Long)

    /**
     * Convert a base64 compressed fingerprint to raw comma-separated uint32 format
     * for the server API.
     */
    fun base64ToRaw(base64Fp: String): String? {
        return nativeBase64ToRaw(base64Fp)
    }

    /** JNI：Base64 指纹 → 原始逗号分隔 uint32 字符串 */
    private external fun nativeBase64ToRaw(base64Fp: String): String?
}
