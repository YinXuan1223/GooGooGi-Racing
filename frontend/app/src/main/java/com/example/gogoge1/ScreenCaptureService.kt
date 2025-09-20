package com.example.gogoge1

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
//import android.icu.util.TimeUnit
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
class ScreenCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(100, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .writeTimeout(100, TimeUnit.SECONDS)
        .build()


    companion object {
        // Actions
        const val ACTION_SETUP = "ACTION_SETUP"
        const val ACTION_CAPTURE_AND_SEND = "ACTION_CAPTURE_AND_SEND"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_RESULT = "ACTION_RESULT" // For broadcasting result to Activity

        // Extras
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val EXTRA_AUDIO_PATH = "AUDIO_PATH"
        const val EXTRA_STATUS = "STATUS"
        const val EXTRA_ERROR = "ERROR"
        const val EXTRA_AUDIO_BASE64 = "AUDIO_BASE64"

        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "ScreenCapture"
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SETUP -> {
                startForegroundWithNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    setupMediaProjection(resultCode, resultData)
                    setupVirtualDisplay()
                }
            }
            ACTION_CAPTURE_AND_SEND -> {
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                if (audioPath != null) {
                    captureAndSend(File(audioPath))
                }
            }
            ACTION_STOP_SERVICE -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
    }

    private fun setupVirtualDisplay() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureAndSend(audioFile: File) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            sendError("無法擷取螢幕畫面")
            return
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            // 裁切掉因 rowPadding 產生的多餘寬度
            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)

            sendToServer(audioFile, finalBitmap)

        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "擷取或處理圖片時出錯", e)
            sendError("擷取或處理圖片時出錯")
        } finally {
            image.close()
            // 點陣圖使用完畢後應回收
            // finalBitmap.recycle()
            // bitmap.recycle()
        }
    }

    private fun sendToServer(audioFile: File, bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val bitmapBytes = stream.toByteArray()

        // 您的 sendToServer 邏輯已整合，這部分是正確的
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/3gpp".toMediaType()))
            .addFormDataPart("image", "screenshot.png", bitmapBytes.toRequestBody("image/png".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("http://192.168.0.188:5000/img") // 使用模擬器IP，並確認端點正確
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ScreenCaptureService", "API請求失敗", e)
                sendError("API請求失敗: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val json = JSONObject(responseBody)
                        val status = json.getString("ai_response")
                        val audioBase64 = json.getString("audio_base64")
                        sendSuccess(status, audioBase64)
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureService", "解析JSON回應時出錯", e)
                        sendError("解析JSON回應時出錯")
                    }
                } else {
                    sendError("伺服器回應錯誤: ${response.code}")
                }
            }
        })
    }

    // 將成功結果廣播回 Activity
    private fun sendSuccess(status: String, audioBase64: String) {
        Intent(ACTION_RESULT).also {
            it.putExtra(EXTRA_STATUS, status)
            it.putExtra(EXTRA_AUDIO_BASE64, audioBase64)
            sendBroadcast(it)
        }
    }

    // 將錯誤訊息廣播回 Activity
    private fun sendError(error: String) {
        Intent(ACTION_RESULT).also {
            it.putExtra(EXTRA_ERROR, error)
            sendBroadcast(it)
        }
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "螢幕擷取服務", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("螢幕擷取服務已啟動")
            .setContentText("正在等待指令...")
            .setSmallIcon(R.mipmap.ic_launcher) // 請確認您有這個圖示資源
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        Log.d("ScreenCaptureService", "服務已銷毀，所有資源已釋放。")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
