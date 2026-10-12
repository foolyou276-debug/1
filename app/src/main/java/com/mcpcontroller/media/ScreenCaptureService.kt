package com.mcpcontroller.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.util.Base64

class ScreenCaptureService : Service() {

    companion object {
        var projectionResultCode: Int = 0
        var projectionData: Intent? = null
        var instance: ScreenCaptureService? = null

        private const val CHANNEL_ID = "ScreenCapture"
        private const val NOTIF_ID = 2
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        initScreenMetrics()
        setupProjection()
    }

    @Suppress("DEPRECATION")
    private fun initScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        // FIX BUG 1: use getRealMetrics, suppress deprecation — windowMetrics needs API 30+ anyway
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            val dm = resources.displayMetrics
            screenDensity = dm.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
    }

    private fun setupProjection() {
        val data = projectionData ?: return
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // FIX BUG 3: wrap in try-catch; if Intent is stale this will throw
            mediaProjection = mpm.getMediaProjection(projectionResultCode, data)

            imageReader?.close()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MCPCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Projection setup failed: ${e.message}")
            // Clear stale data so UI shows permission needed again
            projectionData = null
        }
    }

    fun takeScreenshot(): ByteArray? {
        // Re-init if needed
        if (imageReader == null && projectionData != null) {
            setupProjection()
        }
        val reader = imageReader ?: return null

        Thread.sleep(150) // let frame render (increased for VM stability)

        val image = reader.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            cropped.recycle()
            baos.toByteArray()
        } finally {
            image.close()
        }
    }

    fun takeScreenshotBase64(): String? {
        val bytes = takeScreenshot() ?: return null
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun getScreenDimensions(): Pair<Int, Int> = Pair(screenWidth, screenHeight)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCP Screen Capture")
            .setContentText("Capturing screen for Claude AI")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        instance = null
    }
}
