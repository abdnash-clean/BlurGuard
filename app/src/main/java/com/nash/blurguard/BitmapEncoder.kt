package com.nash.blurguard

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class BitmapEncoder(outputPath: String, width: Int, height: Int) {
    private val encoder: MediaCodec
    private val inputSurface: Surface
    private val muxer: MediaMuxer
    private var isMuxerStarted = false
    private var trackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var isRunning = false
    private var workerThread: Thread? = null
    private val frameQueue = ArrayBlockingQueue<Bitmap>(3) // Drop frames if lagging
    private var startTime: Long = -1

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        isRunning = true
        workerThread = Thread { processQueue() }
        workerThread?.start()
    }

    fun addToVideo(bitmap: Bitmap) {
        if (!isRunning) return
        try {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (!frameQueue.offer(copy)) copy.recycle()
        } catch (e: OutOfMemoryError) {
            // Skip frame
        }
    }

    private fun processQueue() {
        while (isRunning) {
            try {
                val bmp = frameQueue.poll()
                if (bmp != null) {
                    encodeFrame(bmp)
                    bmp.recycle()
                } else {
                    Thread.sleep(10)
                }
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun encodeFrame(bitmap: Bitmap) {
        val canvas = inputSurface.lockCanvas(null)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        inputSurface.unlockCanvasAndPost(canvas)
        drainEncoder(false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) encoder.signalEndOfInputStream()

        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) throw RuntimeException("Format changed twice")
                trackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                isMuxerStarted = true
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)!!
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0

                if (bufferInfo.size != 0) {
                    if (!isMuxerStarted) throw RuntimeException("Muxer not started")
                    if (startTime == -1L) startTime = System.nanoTime()
                    bufferInfo.presentationTimeUs = (System.nanoTime() - startTime) / 1000

                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    fun stop() {
        isRunning = false
        workerThread?.join()
        drainEncoder(true)
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
    }
}