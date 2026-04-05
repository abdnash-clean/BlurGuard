package com.nash.blurguard // Change to your package

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    private var yuvBuffer: ByteArray? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null
    private var yuvBufferCapacity = 0

    fun yuvToRgb(image: ImageProxy, outputBitmap: Bitmap) {
        try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val totalSize = ySize + uSize + vSize

            if (yuvBuffer == null || yuvBufferCapacity < totalSize) {
                yuvBuffer = ByteArray(totalSize)
                yuvBufferCapacity = totalSize

                val yuvType = Type.Builder(rs, Element.U8(rs)).setX(totalSize)
                inputAllocation = Allocation.createTyped(rs, yuvType.create())

                val rgbaType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(image.width).setY(image.height)
                outputAllocation = Allocation.createTyped(rs, rgbaType.create())
            }

            yBuffer.get(yuvBuffer!!, 0, ySize)
            val pixelStride = planes[1].pixelStride

            if (pixelStride == 2) {
                if (ySize + vSize <= yuvBuffer!!.size) {
                    vBuffer.get(yuvBuffer!!, ySize, vSize)
                }
            } else {
                if (ySize + vSize + uSize <= yuvBuffer!!.size) {
                    vBuffer.get(yuvBuffer!!, ySize, vSize)
                    uBuffer.get(yuvBuffer!!, ySize + vSize, uSize)
                }
            }

            inputAllocation!!.copyFrom(yuvBuffer)
            scriptYuvToRgb.setInput(inputAllocation)
            scriptYuvToRgb.forEach(outputAllocation)
            outputAllocation!!.copyTo(outputBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

object ImageUtils {
    fun pixelateRect(image: Bitmap, faceRect: Rect, intensity: Int): Bitmap {
        try {
            val left = max(0, faceRect.left)
            val top = max(0, faceRect.top)
            val right = min(image.width, faceRect.right)
            val bottom = min(image.height, faceRect.bottom)

            val regionWidth = right - left
            val regionHeight = bottom - top

            if (regionWidth <= 0 || regionHeight <= 0) return image

            val outputBitmap = if (image.isMutable) image else image.copy(Bitmap.Config.ARGB_8888, true)
            val faceCrop = Bitmap.createBitmap(outputBitmap, left, top, regionWidth, regionHeight)

            val smallWidth = max(1, regionWidth / intensity)
            val smallHeight = max(1, regionHeight / intensity)

            val tinyFace = Bitmap.createScaledBitmap(faceCrop, smallWidth, smallHeight, false)
            val pixelatedFace = Bitmap.createScaledBitmap(tinyFace, regionWidth, regionHeight, false)

            val canvas = Canvas(outputBitmap)
            canvas.drawBitmap(pixelatedFace, left.toFloat(), top.toFloat(), Paint())

            faceCrop.recycle()
            tinyFace.recycle()
            pixelatedFace.recycle()

            return outputBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return image
        }
    }

    fun blurRect(rs: RenderScript, image: Bitmap, faceRect: Rect, radius: Float = 25f): Bitmap {
        try {
            // 1. Safety Checks (Keep box inside screen)
            val left = max(0, faceRect.left)
            val top = max(0, faceRect.top)
            val right = min(image.width, faceRect.right)
            val bottom = min(image.height, faceRect.bottom)

            val w = right - left
            val h = bottom - top

            if (w <= 0 || h <= 0) return image

            val outputBitmap = if (image.isMutable) image else image.copy(Bitmap.Config.ARGB_8888, true)

            // 2. Crop the face
            val faceCrop = Bitmap.createBitmap(outputBitmap, left, top, w, h)

            // 3. PERFORMANCE TRICK: Shrink the image to 1/4 size before blurring.
            // Blurring a 400x400 image is slow. Blurring a 100x100 image is instant.
            val smallW = max(1, w / 4)
            val smallH = max(1, h / 4)
            val smallFace = Bitmap.createScaledBitmap(faceCrop, smallW, smallH, true)

            // 4. Apply RenderScript Gaussian Blur
            val input = Allocation.createFromBitmap(rs, smallFace)
            val output = Allocation.createTyped(rs, input.type)

            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            // RenderScript max blur radius is 25.0f
            script.setRadius(min(25f, max(1f, radius)))
            script.setInput(input)
            script.forEach(output)

            output.copyTo(smallFace)

            // 5. Scale it back up to normal size
            val blurredFace = Bitmap.createScaledBitmap(smallFace, w, h, true)

            // 6. Draw the blurred glass patch over the original image
            val canvas = Canvas(outputBitmap)
            canvas.drawBitmap(blurredFace, left.toFloat(), top.toFloat(), Paint())

            // 7. Clean up memory to prevent Garbage Collection lag
            faceCrop.recycle()
            smallFace.recycle()
            blurredFace.recycle()
            input.destroy()
            output.destroy()
            script.destroy()

            return outputBitmap

        } catch (e: Exception) {
            e.printStackTrace()
            return image
        }
    }
}