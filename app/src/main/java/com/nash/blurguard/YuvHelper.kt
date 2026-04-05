package com.nash.blurguard
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import io.github.crow_misia.libyuv.AbgrBuffer
import io.github.crow_misia.libyuv.I420Buffer
import io.github.crow_misia.libyuv.Nv12Buffer
import io.github.crow_misia.libyuv.Nv21Buffer
import io.github.crow_misia.libyuv.Plane
import java.nio.ByteBuffer

object YuvHelper {

    /**
     * Helper method to dynamically implement the library's Plane interface.
     */
    private fun createPlane(byteBuffer: ByteBuffer, stride: Int): Plane {
        return object : Plane {
            override val buffer: ByteBuffer = byteBuffer
            override val rowStride: Int = stride
        }
    }

    @JvmStatic
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        // Extract raw buffers and strides from CameraX
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yStride = planes[0].rowStride
        val uStride = planes[1].rowStride
        val vStride = planes[2].rowStride

        val uvPixelStride = planes[1].pixelStride

        // Allocate the destination ARGB buffer
        val argbOut = AbgrBuffer.allocate(width, height)

        if (uvPixelStride == 1) {
            // PLANAR FORMAT (I420)
            val planeY = createPlane(yBuffer, yStride)
            val planeU = createPlane(uBuffer, uStride)
            val planeV = createPlane(vBuffer, vStride)

            val yuvBuffer = I420Buffer.wrap(planeY, planeU, planeV, width, height)
            yuvBuffer.convertTo(argbOut)
            yuvBuffer.close()

        } else {
            // INTERLEAVED FORMAT (NV21 or NV12)
            if (vBuffer.position() < uBuffer.position()) {
                // NV21 (VU interleaved plane)
                val planeY = createPlane(yBuffer, yStride)
                val planeVU = createPlane(vBuffer, vStride)

                val yuvBuffer = Nv21Buffer.wrap(planeY, planeVU, width, height)
                yuvBuffer.convertTo(argbOut)
                yuvBuffer.close()
            } else {
                // NV12 (UV interleaved plane)
                val planeY = createPlane(yBuffer, yStride)
                val planeUV = createPlane(uBuffer, uStride)

                val yuvBuffer = Nv12Buffer.wrap(planeY, planeUV, width, height)
                yuvBuffer.convertTo(argbOut)
                yuvBuffer.close()
            }
        }

        // Convert libyuv's native buffer into an Android Bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(argbOut.asBuffer())

        // Prevent memory leaks
        argbOut.close()

        return bitmap
    }
}