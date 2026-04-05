package com.nash.blurguard // Check your package name!

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.DequantizeOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class RawYoloDetector(context: Context, modelName: String) {
    private var interpreter: Interpreter? = null

    // MUST MATCH YOUR COLAB EXPORT SIZE (320 or 416 or 640)
    private val INPUT_SIZE = 640

    private lateinit var imageProcessor: ImageProcessor
    private lateinit var outputProcessor: TensorProcessor
    private lateinit var outputShape: IntArray
    private lateinit var inputDataType: DataType
    private lateinit var outputDataType: DataType

    init {
        try {
            val tfliteModel = loadModelFile(context, modelName) // Using our new manual function
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                useXNNPACK = true
            }

            interpreter = Interpreter(tfliteModel, options)

            inputDataType = interpreter!!.getInputTensor(0).dataType()
            outputDataType = interpreter!!.getOutputTensor(0).dataType()
            outputShape = interpreter!!.getOutputTensor(0).shape()

            val processorBuilder = ImageProcessor.Builder()
                .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))

            if (inputDataType == DataType.FLOAT32) {
                processorBuilder.add(NormalizeOp(0f, 255f))
            }
            imageProcessor = processorBuilder.build()

            val outProcessorBuilder = TensorProcessor.Builder()
            if (outputDataType != DataType.FLOAT32) {
                val params = interpreter!!.getOutputTensor(0).quantizationParams()
                outProcessorBuilder.add(DequantizeOp(params.zeroPoint.toFloat(), params.scale))
            }
            outputProcessor = outProcessorBuilder.build()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun detect(bitmap: Bitmap): List<Rect> {
        if (interpreter == null) return emptyList()

        var tensorImage = TensorImage(inputDataType)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val rawOutputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType)
        interpreter!!.run(tensorImage.buffer, rawOutputBuffer.buffer)

        val processedOutput = outputProcessor.process(rawOutputBuffer)
        val data = processedOutput.floatArray

        val detections = mutableListOf<Rect>()
        val threshold = 0.30f // Lowered to 30% to ensure we see the blur

        // FORMAT A: Standard YOLOv8[1, Features, Anchors (e.g. 8400)]
        if (outputShape.size == 3 && outputShape[2] > 500) {
            val numFeatures = outputShape[1]
            val numAnchors = outputShape[2]

            for (i in 0 until numAnchors) {
                val x = data[0 * numAnchors + i]
                val y = data[1 * numAnchors + i]
                val w = data[2 * numAnchors + i]
                val h = data[3 * numAnchors + i]

                var maxScore = 0f
                for (c in 4 until numFeatures) {
                    val score = data[c * numAnchors + i]
                    if (score > maxScore) maxScore = score
                }

                if (maxScore > threshold) {
                    detections.add(createRect(x, y, w, h, bitmap))
                }
            }
        }
        // FORMAT B: End-to-End NMS-Free YOLO[1, Boxes (e.g. 300), Features]
        else if (outputShape.size == 3 && outputShape[1] > 0 && outputShape[2] >= 5) {
            val numBoxes = outputShape[1]
            val numFeatures = outputShape[2]

            for (i in 0 until numBoxes) {
                val offset = i * numFeatures
                val x = data[offset + 0]
                val y = data[offset + 1]
                val w = data[offset + 2]
                val h = data[offset + 3]

                var maxScore = 0f
                for (c in 4 until numFeatures) {
                    val score = data[offset + c]
                    if (score > maxScore) maxScore = score
                }

                if (maxScore > threshold) {
                    detections.add(createRect(x, y, w, h, bitmap))
                }
            }
        }

        // Log to console so you can see if it's finding faces
        if (detections.isNotEmpty()) {
            android.util.Log.d("BlurGuard", "Found ${detections.size} objects!")
        }

        return detections
    }

    private fun createRect(x: Float, y: Float, w: Float, h: Float, bitmap: Bitmap): Rect {
        val scaleX: Float
        val scaleY: Float

        // AUTO-DETECT SCALING:
        // If x is < 2.0, the model output Normalized coords (0.0 to 1.0)
        // If x is > 2.0, it output Absolute pixels (0 to INPUT_SIZE)
        if (x <= 2.0f && y <= 2.0f && w <= 2.0f && h <= 2.0f) {
            scaleX = bitmap.width.toFloat()
            scaleY = bitmap.height.toFloat()
        } else {
            scaleX = bitmap.width.toFloat() / INPUT_SIZE
            scaleY = bitmap.height.toFloat() / INPUT_SIZE
        }

        val left = ((x - w / 2) * scaleX).toInt()
        val top = ((y - h / 2) * scaleY).toInt()
        val right = ((x + w / 2) * scaleX).toInt()
        val bottom = ((y + h / 2) * scaleY).toInt()

        return Rect(
            max(0, left),
            max(0, top),
            min(bitmap.width, right),
            min(bitmap.height, bottom)
        )
    }


    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}