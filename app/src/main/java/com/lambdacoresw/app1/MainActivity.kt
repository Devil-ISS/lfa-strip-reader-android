package com.example.myapp   // ← change this to your actual package

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var resultImage: ImageView
    private lateinit var resultText: TextView
    private lateinit var imageCapture: ImageCapture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView   = findViewById(R.id.previewView)
        btnCapture    = findViewById(R.id.btnCapture)
        resultImage   = findViewById(R.id.resultImage)
        resultText    = findViewById(R.id.resultText)

        // 1. Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("MainActivity", "Failed to load OpenCV")
        } else {
            Log.i("MainActivity", "OpenCV loaded successfully")
        }

        // 2. Hook capture button
        btnCapture.setOnClickListener { takePhoto() }

        // 3. Check/request camera permission, then start preview
        checkCameraPermission()
    }

    // --- Permissions ----------------------------------
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    // --- CameraX preview + capture --------------------
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview use-case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // ImageCapture use-case
            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        // Temporary file in cache
        val tmpFile = File(cacheDir, "lfa.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(tmpFile).build()

        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(tmpFile.absolutePath)
                    processBitmap(bmp)
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    // --- Processing pipeline --------------------------
    private fun processBitmap(bmp: Bitmap) {
        thread {
            // 1. Bitmap → Mat
            val srcMat = Mat()
            Utils.bitmapToMat(bmp, srcMat)

            // 2. Locate & warp cassette
            val cassette = detectCassette(srcMat)
            if (cassette == null) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn’t find cassette", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            // 3. Run strip-analysis and get (annotated Bitmap, results)
            val (outBmp, result) = processStripMat(cassette)

            // 4. Display on UI
            runOnUiThread {
                resultImage.setImageBitmap(outBmp)
                resultText.text = "Peaks: ${result.peakPositions}  Ratio: ${"%.2f".format(result.ratio)}"
            }
        }
    }

    private fun detectCassette(mat: Mat): Mat? {
        // Grayscale + blur
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // Canny edges
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        // Find contours & pick largest quadrilateral
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        var bestQuad: MatOfPoint2f? = null
        var bestArea = 0.0
        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() == 4L) {
                val area = Imgproc.contourArea(approx)
                if (area > bestArea) {
                    bestArea = area
                    bestQuad = approx
                }
            }
        }
        if (bestQuad == null) return null

        // Sort points and warp to a fixed rectangle
        val pts = bestQuad.toArray().sortedBy { it.x + it.y }
        val src = MatOfPoint2f(*pts.toTypedArray())
        val dstPts = arrayOf(
            Point(0.0, 0.0),
            Point(600.0, 0.0),
            Point(600.0, 200.0),
            Point(0.0, 200.0)
        )
        val dst = MatOfPoint2f(*dstPts)
        val M = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(mat, out, M, Size(600.0, 200.0))
        return out
    }

    private fun processStripMat(cassette: Mat): Pair<Bitmap, AnalysisResult> {
        // Extract red channel & invert
        val red = Mat(); Core.extractChannel(cassette, red, 2)
        val inv = Mat(); Core.bitwise_not(red, inv)

        // Morphological open → background
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(31.0, 1.0))
        val bg = Mat(); Imgproc.morphologyEx(inv, bg, Imgproc.MORPH_OPEN, kernel)

        // Top-hat & median-blur
        val topHat = Mat(); Core.subtract(inv, bg, topHat)
        val clean = Mat(); Imgproc.medianBlur(topHat, clean, 5)

        // Build 1D row-profile
        val centre = clean.rows() / 2
        val halfSpan = 2
        val xStart = 225
        val xEnd   = 325
        val prof = DoubleArray(xEnd - xStart) { 0.0 }
        for (r in (centre - halfSpan)..(centre + halfSpan)) {
            for (x in xStart until xEnd) {
                prof[x - xStart] += clean.get(r, x)[0]
            }
        }
        val rows = (2 * halfSpan + 1).toDouble()
        for (i in prof.indices) prof[i] /= rows

        // Peak & area analysis
        val result = analyseProfileKotlin(prof, ampMin = 2.0)

        // Draw vertical lines at each peak
        val display = Mat()
        Imgproc.cvtColor(cassette, display, Imgproc.COLOR_BGR2RGBA)
        for (p in result.peakPositions) {
            val x = xStart + p
            Imgproc.line(display,
                Point(x.toDouble(), 0.0),
                Point(x.toDouble(), cassette.rows().toDouble()),
                Scalar(255.0, 0.0, 0.0, 255.0), 2)
        }

        // Convert back to Bitmap
        val outBmp = Bitmap.createBitmap(display.cols(), display.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(display, outBmp)
        return Pair(outBmp, result)
    }

    private fun analyseProfileKotlin(y: DoubleArray, ampMin: Double): AnalysisResult {
        // Find local maxima above ampMin
        val peaks = mutableListOf<Int>()
        for (i in 1 until y.size - 1) {
            if (y[i] > y[i - 1] && y[i] >= y[i + 1] && y[i] >= ampMin) {
                peaks.add(i)
            }
        }
        if (peaks.isEmpty()) return AnalysisResult(emptyList(), emptyList(), null)

        // Keep the top 2 peaks
        val top2 = peaks.sortedByDescending { y[it] }.take(2).sorted()
        val areas = mutableListOf<Double>()
        for (p in top2) {
            // find valley boundaries
            var lv = p; while (lv > 0 && y[lv - 1] <= y[lv]) lv--
            var rv = p; while (rv < y.size - 1 && y[rv + 1] <= y[rv]) rv++
            areas.add(y.slice(lv..rv).sum())
        }
        val ratio = if (areas.size == 2 && areas[0] != 0.0) areas[1] / areas[0] else null
        return AnalysisResult(top2, areas, ratio)
    }

    data class AnalysisResult(
        val peakPositions: List<Int>,
        val areas:           List<Double>,
        val ratio:          Double?
    )
}
