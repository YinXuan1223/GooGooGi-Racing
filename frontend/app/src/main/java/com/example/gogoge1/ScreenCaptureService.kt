package com.example.gogoge1

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
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
import java.nio.ByteBuffer
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

    private val monitoringHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var initialAudioFile: File? = null // 用來儲存最初的音檔
    private val MONITORING_INTERVAL_MS = 7000L

    // Variable to store the hash of the last successfully sent screen
    private var lastScreenHash: Int? = null
    private var recorder: MediaRecorder? = null

    companion object {
        // Actions
        const val ACTION_SETUP = "ACTION_SETUP"
        const val ACTION_START_MONITORING = "ACTION_START_MONITORING"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_RESULT = "ACTION_RESULT" // For broadcasting result to Activity
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"

        // Extras
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val EXTRA_AUDIO_PATH = "AUDIO_PATH"
        const val EXTRA_STATUS = "STATUS"
        const val EXTRA_ERROR = "ERROR"
        const val EXTRA_AUDIO_BASE64 = "AUDIO_BASE64"
        const val EXTRA_MISSION_ACHIEVED = "MISSION_ACHIEVED"

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
            ACTION_START_MONITORING -> {
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                if (audioPath != null) {
                    // 1. 停止任何可能正在運行的舊監控，確保每次都是新的開始
                    stopMonitoring()

                    // 2. 儲存這次任務要重複使用的音檔
                    initialAudioFile = File(audioPath)

                    // 3. 立即執行第一次的畫面擷取與傳送
                    captureAndProcessScreen()
                }
            }
            ACTION_STOP_SERVICE -> {
                stopMonitoring() // 確保在服務停止時，監控也停止
                stopSelf()
            }
            ACTION_START_RECORDING -> {
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                if (audioPath != null) {
                    startRecording(File(audioPath))
                }
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    // 這個 Runnable 是定時器的本體，會被重複執行
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                captureAndProcessScreen() // 執行檢查
                monitoringHandler.postDelayed(this, MONITORING_INTERVAL_MS) // 安排下一次
            }
        }
    }

    // 啟動監控的函式
    private fun startMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true
            Log.d("ScreenCaptureService", "螢幕監控已啟動。")
            // 使用 post 而非 postDelayed，讓監控在網路回應後能立即開始下一次輪詢
            monitoringHandler.post(monitoringRunnable)
        }
    }

    // 停止監控的函式
    private fun stopMonitoring() {
        if (isMonitoring) {
            isMonitoring = false
            monitoringHandler.removeCallbacks(monitoringRunnable)
            Log.d("ScreenCaptureService", "螢幕監控已停止。")
        }
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

    private fun captureAndProcessScreen(){
        val audioFile = initialAudioFile ?: return
        val image = imageReader?.acquireLatestImage() ?: return

        var finalBitmap: Bitmap? = null
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
            finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle() // Recycle intermediate bitmap

            // --- Start of Integrated Logic ---
            val newHash = generateScreenHash(finalBitmap)

            if (lastScreenHash == null || newHash != lastScreenHash) {
                Log.d("ScreenChange", "✅ [CHANGE DETECTED] 畫面已發生變更！準備上傳...")
                // The hash is different, so we send the data to the server.
                sendToServer(audioFile, finalBitmap)
                // We update the hash *only after* deciding to send.
                lastScreenHash = newHash
            } else {
                Log.d("ScreenChange", "⚪️ [NO CHANGE] 畫面無變化，本次不執行上傳。")
                // Inform the calling activity that no action was taken.
                finalBitmap.recycle() // Recycle bitmap since it's not being used.
            }
            // --- End of Integrated Logic ---

        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "擷取或處理圖片時出錯", e)
            sendError("擷取或處理圖片時出錯")
            finalBitmap?.recycle()
        } finally {
            image.close()
        }
    }

    /**
     * Generates a fingerprint of the Bitmap after cropping the status bar
     * to focus only on the application's content area.
     */
    private fun generateScreenHash(bitmap: Bitmap): Int {
        val statusBarHeight = getStatusBarHeight()
        // Check if there is enough height to crop
        if (bitmap.height <= statusBarHeight) {
            return bitmap.hashCode() // If height is insufficient, return original hash
        }

        // 1. Crop the status bar to keep only the app content area
        val contentBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            statusBarHeight,
            bitmap.width,
            bitmap.height - statusBarHeight
        )

        // 2. Scale down the cropped image for performance and to ignore minor differences
        val scaledBitmap = Bitmap.createScaledBitmap(contentBitmap, 32, 64, true)
        contentBitmap.recycle()

        // 3. Get the pixel data of the scaled-down image
        val byteBuffer = ByteBuffer.allocate(scaledBitmap.byteCount)
        scaledBitmap.copyPixelsToBuffer(byteBuffer)
        scaledBitmap.recycle()

        // 4. Calculate and return a simple hash code as the fingerprint
        return byteBuffer.array().contentHashCode()
    }

    /**
     * Gets the height of the status bar to exclude it from the screen comparison.
     */
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            // Provide a reasonable default value if the resource is not found
            (24 * resources.displayMetrics.density).toInt()
        }
    }

    private fun sendToServer(audioFile: File, bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val bitmapBytes = stream.toByteArray()
        bitmap.recycle() // Recycle the bitmap after it has been converted to a byte array

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/3gpp".toMediaType()))
            .addFormDataPart("image", "screenshot.png", bitmapBytes.toRequestBody("image/png".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("http://192.168.50.184:5000/img")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ScreenCaptureService", "API請求失敗", e)
                sendError("API請求失敗: ${e.message}")
                stopMonitoring()
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val json = JSONObject(responseBody)
                        val missionAchieved = json.optBoolean("mission_achieved", false)
                        val status = json.getString("ai_response")
                        val audioBase64 = json.getString("audio_base64")
                        sendSuccess(status, audioBase64, missionAchieved)
                        if (missionAchieved) {
                            Log.d("ScreenCaptureService", "任務完成，停止監控。")
                            stopMonitoring()
                        } else {
                            Log.d("ScreenCaptureService", "任務未完成，準備下一次監控。")
                            // 在這裡啟動監控，讓它在收到回應後才開始等下一個變化
                            startMonitoring()
                        }
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

    private fun sendSuccess(status: String, audioBase64: String, missionAchieved: Boolean) {
        Intent(ACTION_RESULT).also {
            it.putExtra(EXTRA_STATUS, status)
            it.putExtra(EXTRA_AUDIO_BASE64, audioBase64)
            it.putExtra(EXTRA_MISSION_ACHIEVED, missionAchieved)
            sendBroadcast(it)
        }
    }

    private fun sendError(error: String) {
        Intent(ACTION_RESULT).also {
            it.putExtra(EXTRA_ERROR, error)
            it.putExtra(EXTRA_MISSION_ACHIEVED, true)
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startRecording(audioFile: File) {
        // 確保服務是前景服務，這是能在背景錄音的關鍵
        startForegroundWithNotification() // 記得更新通知文字

        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(audioFile.absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            try {
                prepare()
                start()
                // 可以透過廣播通知 Activity 錄音已成功開始
                Log.d("ScreenCaptureService", "錄音已開始")
            } catch (e: IOException) {
                Log.e("ScreenCaptureService", "MediaRecorder prepare() failed", e)
                // 透過廣播通知 Activity 發生錯誤
                sendError("錄音裝置啟動失敗")
                stopSelf() // 啟動失敗就停止服務
            }
        }
    }

    private fun stopRecording() {
        recorder?.let {
            try {
                it.stop()
                it.release()
                Log.d("ScreenCaptureService", "錄音已停止")
            } catch (e: IllegalStateException) {
                Log.e("ScreenCaptureService", "MediaRecorder stop failed", e)
            }
        }
        recorder = null
        // 這裡可以選擇是否更新通知，或等待下一個指令
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
