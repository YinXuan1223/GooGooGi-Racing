package com.example.gogoge1

import android.app.*
import android.content.ContentValues
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

class ScreenCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isCapturing = false
    private val captureInterval = 5000L

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureAndUpload() // 執行擷取與上傳
                handler.postDelayed(this, captureInterval)
            }
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "ScreenCapture"
        const val ACTION_START_CONTINUOUS_CAPTURE = "ACTION_START_CONTINUOUS_CAPTURE"
        const val ACTION_STOP_CONTINUOUS_CAPTURE = "ACTION_STOP_CONTINUOUS_CAPTURE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CONTINUOUS_CAPTURE -> {
                startContinuousCapture()
                return START_NOT_STICKY
            }
            ACTION_STOP_CONTINUOUS_CAPTURE -> {
                stopContinuousCapture()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundWithNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData != null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (isCapturing) {
                        stopContinuousCapture()
                    }
                    super.onStop()
                }
            }, handler)
            setupVirtualDisplay()
        }

        return START_NOT_STICKY
    }

    private fun startContinuousCapture() {
        if (!isCapturing && mediaProjection != null) {
            isCapturing = true
            handler.post(captureRunnable)
            updateNotification()
            handler.post { Toast.makeText(this, "已開始連續截圖", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun stopContinuousCapture() {
        if (isCapturing) {
            isCapturing = false
            handler.removeCallbacks(captureRunnable)
            updateNotification()
            handler.post { Toast.makeText(this, "已暫停連續截圖", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupVirtualDisplay() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val screenDensity = displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun captureAndUpload() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            if (bitmapWidth <= 0 || image.height <= 0) {
                return
            }

            val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

            // 將處理好的 Bitmap 發送到後端
            sendBitmapToApi(finalBitmap)
            // 如果您還想同時儲存到相簿，可以取消下面這行的註解
            // saveBitmapToGallery(finalBitmap)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // 確保 image 物件一定會被關閉，避免資源洩漏
            image.close()
        }
    }

    private fun sendBitmapToApi(bitmap: Bitmap) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos) // 品質設為90以稍微減小檔案大小
                val bitmapBytes = baos.toByteArray()

                val client = OkHttpClient()
                // 模擬器請用 10.0.2.2，實體手機請換成您電腦的IP
                val url = "http://10.0.2.2:5000/upload"

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "screenshot_file",
                        "screenshot.png",
                        bitmapBytes.toRequestBody("image/png".toMediaType())
                    )
                    .build()

                val request = Request.Builder().url(url).post(requestBody).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    println("上傳成功: Server response: ${response.body?.string()}")
                } else {
                    println("上傳失敗: ${response.code} ${response.message}")
                }
            } catch (e: Exception) {
                println("上傳時發生錯誤: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // (可選) 儲存到相簿的函式
    private fun saveBitmapToGallery(bitmap: Bitmap) {
        // ... 此函式內容不變 ...
    }


    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "螢幕擷取服務", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("螢幕擷取服務已啟動")
            .setContentText("準備開始連續截圖...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val isCapturing = this.isCapturing
        val contentText: String
        val actionTitle: String
        val actionIntent: PendingIntent

        if (isCapturing) {
            contentText = "每5秒擷取一次畫面中..."
            actionTitle = "暫停"
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP_CONTINUOUS_CAPTURE }
            actionIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            contentText = "已暫停擷取。點擊按鈕以繼續。"
            actionTitle = "開始"
            val startIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_START_CONTINUOUS_CAPTURE }
            actionIntent = PendingIntent.getService(this, 2, startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val stopServiceIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopServicePendingIntent = PendingIntent.getService(this, 3, stopServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("螢幕擷取服務")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, actionTitle, actionIntent)
            .addAction(0, "停止服務", stopServicePendingIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuousCapture()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

