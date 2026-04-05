package com.nash.blurguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.renderscript.RenderScript
import android.util.Size
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var outputImageView: ImageView
    private lateinit var tvFps: TextView
    private lateinit var tvCpuUsage: TextView
    private lateinit var tvRamUsage: TextView
    private lateinit var tvThermal: TextView
    private lateinit var btnRecord: MaterialButton
    private lateinit var rs: RenderScript
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: RawYoloDetector
    private lateinit var converter: YuvToRgbConverter

    private var reusableBitmap: Bitmap? = null
    private var videoRecorder: BitmapEncoder? = null
    private var isRecording = false

    private var frameCounter = 0
    private var cachedFaces = listOf<Rect>()
    private var framesRendered = 0
    private var lastFpsTime = System.currentTimeMillis()

    // CPU Logic
    private var lastCpuTime = 0L
    private var lastAppTime = 0L
    private val numCores = Runtime.getRuntime().availableProcessors()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        outputImageView = findViewById(R.id.outputImageView)
        tvFps = findViewById(R.id.tvFps)
        tvCpuUsage = findViewById(R.id.tvCpuUsage)
        tvRamUsage = findViewById(R.id.tvRamUsage)
        tvThermal = findViewById(R.id.tvThermal)
        btnRecord = findViewById(R.id.btnRecord)
        rs = RenderScript.create(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        converter = YuvToRgbConverter(this)

        // Ensure this filename matches exactly what is in your assets folder!
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // This runs in the background, freeing up the UI thread!
                detector = RawYoloDetector(this@MainActivity, "finetuned_int8_416.tflite")

                // 2. Switch back to the Main thread to update the UI
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "GPU Model Loaded!", Toast.LENGTH_SHORT).show()
                    // Enable your camera or buttons here
                    // hideLoadingSpinner()
                    // startCamera()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }

        setupRecordingButton()
        startMetricsLoop()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (reusableBitmap == null) {
                    reusableBitmap = createBitmap(imageProxy.width, imageProxy.height)
                }

                // 1. Fast GPU YUV to RGB conversion
                //converter.yuvToRgb(imageProxy, reusableBitmap);
                reusableBitmap = YuvHelper.imageProxyToBitmap(imageProxy)

                // 2. Skip frames for AI to maintain high FPS
                cachedFaces = detector.detect(reusableBitmap!!)
                frameCounter++

                // 3. Pixelate Faces
                var processedBitmap = reusableBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
                for (face in cachedFaces) {
                  //  print("Face detected: ${face.left}, ${face.top}, ${face.right}, ${face.bottom}")
                    processedBitmap = ImageUtils.pixelateRect(processedBitmap, face, 10)
//                    processedBitmap = ImageUtils.blurRect(rs, processedBitmap, face, 25f)
                }

                // 4. Update UI via Coroutine (No more runOnUiThread mess)
                lifecycleScope.launch(Dispatchers.Main) {
                    outputImageView.setImageBitmap(processedBitmap)
                    framesRendered++
                }

                // 5. Async Recording
                if (isRecording) {
                    videoRecorder?.addToVideo(processedBitmap)
                } else {
                    // If not recording, we must recycle the copied bitmap to prevent RAM bloat
                    // (The recorder handles recycling inside its queue if recording)
                    // processedBitmap.recycle() - Wait, ImageView needs it to draw!
                    // In a perfect system we double buffer. Here the GC will clean it up quickly.
                }

                imageProxy.close()
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupRecordingButton() {
        btnRecord.setOnClickListener {
            if (!isRecording) {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val path = File(dir, "BlurGuard_${System.currentTimeMillis()}.mp4").absolutePath

                try {
                    videoRecorder = BitmapEncoder(path, 640, 480) // Must match camera resolution!
                    isRecording = true
                    btnRecord.text = "STOP"
                    btnRecord.setBackgroundColor(Color.GRAY)
                    Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                isRecording = false
                videoRecorder?.stop()
                videoRecorder = null
                btnRecord.text = "RECORD"
                btnRecord.setBackgroundColor(Color.parseColor("#D32F2F"))
                Toast.makeText(this, "Saved to Downloads!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startMetricsLoop() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val now = System.currentTimeMillis()
                val fps = framesRendered * 1000f / (now - lastFpsTime)
                tvFps.text = String.format("FPS: %.1f", fps)
                framesRendered = 0
                lastFpsTime = now

                val currentCpuTime = Process.getElapsedCpuTime()
                val currentAppTime = SystemClock.uptimeMillis()
                if (lastAppTime > 0L) {
                    val cpuDelta = currentCpuTime - lastCpuTime
                    val timeDelta = currentAppTime - lastAppTime
                    if (timeDelta > 0) {
                        var usage = (cpuDelta.toDouble() / timeDelta) * 100.0 / numCores
                        if (usage > 100.0) usage = 100.0
                        tvCpuUsage.text = String.format("CPU: %.1f%%", usage)
                    }
                }
                lastCpuTime = currentCpuTime
                lastAppTime = currentAppTime

                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
                tvRamUsage.text = "RAM: $usedMem MB"

                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        videoRecorder?.stop()
        rs.destroy()
    }
}