package com.example.gogoge1

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnSend: Button
    private lateinit var btnToggleCapture: Button

    //語音功能變數
    private var isRecording = false
    private var isCapturing = false
    private var audioFile: File? = null
    private var recorder: android.media.MediaRecorder? = null
    private var player: MediaPlayer? = null

    // 廣播接收器，用於接收來自 Service 的結果
    private val serviceResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(ScreenCaptureService.EXTRA_STATUS) ?: "未知狀態"
            val error = intent?.getStringExtra(ScreenCaptureService.EXTRA_ERROR)
            val audioBase64 = intent?.getStringExtra(ScreenCaptureService.EXTRA_AUDIO_BASE64)

            if (error != null) {
                tvStatus.text = "錯誤: $error"
                Toast.makeText(this@MainActivity, "錯誤: $error", Toast.LENGTH_LONG).show()
            } else {
                tvStatus.text = status
                if (audioBase64 != null) {
                    playBase64Audio(audioBase64)
                }
            }
        }
    }

    // 處理螢幕擷取權限請求的結果
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_SETUP
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            // 為了讓 Service 能在背景啟動，需要使用 startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isCapturing = true
            btnToggleCapture.text = "停止偵測畫面"
            tvStatus.text = "螢幕擷取服務已啟動"
        } else {
            Toast.makeText(this, "未授予螢幕擷取權限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnSend = findViewById(R.id.btnSend)
        btnToggleCapture = findViewById(R.id.btnToggleCapture) // 假設你有一個按鈕來控制擷取

        // 註冊廣播接收器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceResultReceiver, IntentFilter(ScreenCaptureService.ACTION_RESULT), RECEIVER_EXPORTED)
        } else {
            registerReceiver(serviceResultReceiver, IntentFilter(ScreenCaptureService.ACTION_RESULT))
        }

        // **【修改處】** 請求所有必要的權限
        requestAllPermissions()

        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        btnSend.setOnClickListener {
            if (audioFile?.exists() == true) {
                // 命令 Service 進行擷取並傳送
                Intent(this, ScreenCaptureService::class.java).also {
                    it.action = ScreenCaptureService.ACTION_CAPTURE_AND_SEND
                    it.putExtra(ScreenCaptureService.EXTRA_AUDIO_PATH, audioFile!!.absolutePath)
                    startService(it)
                }
                tvStatus.text = "指令已發送，處理中..."
            } else {
                Toast.makeText(this, "請先錄音", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleCapture.setOnClickListener {
            if (isCapturing) {
                stopScreenCaptureService()
            } else {
                startScreenCapture()
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "請先授予錄音權限", Toast.LENGTH_SHORT).show()
            requestAllPermissions()
            return
        }
        // 1. 修改檔案名稱
        audioFile = File(cacheDir, "input.m4a")
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            // 2. 修改輸出格式
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(audioFile!!.absolutePath)
            // 3. 修改音訊編碼
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            try {
                prepare()
                start()
                isRecording = true
                tvStatus.text = "錄音中..."
                btnRecord.text = "停止錄音"
            } catch (e: IOException) {
                Log.e("MainActivity", "MediaRecorder prepare() failed", e)
                Toast.makeText(this@MainActivity, "錄音裝置啟動失敗，請稍後再試", Toast.LENGTH_SHORT).show()
                isRecording = false
                btnRecord.text = "開始錄音"
                tvStatus.text = "錄音失敗"
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: IllegalStateException) {
            Log.e("MainActivity", "MediaRecorder stop failed", e)
        }
        recorder = null
        isRecording = false
        tvStatus.text = "錄音結束"
        btnRecord.text = "開始錄音"
    }

    private fun startScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopScreenCaptureService() {
        Intent(this, ScreenCaptureService::class.java).also {
            it.action = ScreenCaptureService.ACTION_STOP_SERVICE
            startService(it)
        }
        isCapturing = false
        btnToggleCapture.text = "開始偵測畫面"
        tvStatus.text = "螢幕擷取服務已停止"
    }

    private fun playBase64Audio(base64Audio: String) {
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tempMp3 = File.createTempFile("reply", "mp3", cacheDir)
            FileOutputStream(tempMp3).use { it.write(audioBytes) }

            player?.release()
            player = MediaPlayer().apply {
                setDataSource(tempMp3.absolutePath)
                prepareAsync()
                setOnPreparedListener {
                    tvStatus.text = "收到回覆，正在播放..."
                    it.start()
                }
                setOnCompletionListener {
                    tvStatus.text = "播放完畢"
                    it.release()
                    tempMp3.delete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MediaPlayer", "Playback Error - what: $what, extra: $extra")
                    tvStatus.text = "播放失敗"
                    release()
                    tempMp3.delete()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "播放Base64音訊時出錯", e)
            tvStatus.text = "播放音訊時出錯"
        }
    }

    // **【修改處】** 建立一個函式統一請求所有需要的權限
    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 1. 錄音權限 (所有版本都需要)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // 2. 通知權限 (Android 13 / API 33 以上需要)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // **【重要】** 雖然 FOREGROUND_SERVICE_MEDIA_PROJECTION 在 Manifest 中宣告，
        // 但使用者是透過 MediaProjectionManager 的對話框來授權，因此不需要在此手動請求。

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceResultReceiver) // 取消註冊
        recorder?.release()
        player?.release()
        stopScreenCaptureService() // 確保App關閉時服務也停止
    }
}

