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
import android.provider.Settings
import androidx.core.net.toUri


class MainActivity : AppCompatActivity() {

    // --- UI 元件 ---
    private lateinit var tvStatus: TextView
    private lateinit var btnControlSession: Button

    // --- 狀態與功能變數 ---
    private var isSessionActive = false // 【修改】合併 isRecording 和 isCapturing 的狀態
    private var audioFile: File? = null
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    // --- 廣播接收器 (保持不變) ---
    private val serviceResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(ScreenCaptureService.EXTRA_STATUS) ?: "未知狀態"
            val error = intent?.getStringExtra(ScreenCaptureService.EXTRA_ERROR)
            val audioBase64 = intent?.getStringExtra(ScreenCaptureService.EXTRA_AUDIO_BASE64)

            if (error != null) {
                tvStatus.text = "錯誤: $error"
                Toast.makeText(this@MainActivity, "錯誤: $error", Toast.LENGTH_LONG).show()
            } else {
                tvStatus.text = "AI 回應: $status"
                if (audioBase64 != null) {
                    playBase64Audio(audioBase64)
                }
            }
        }
    }

    private val sessionToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingService.ACTION_TOGGLE_SESSION) {
                // 模擬點擊主按鈕，重複使用現有邏輯
                btnControlSession.performClick()
            }
        }
    }

    // --- 權限請求結果處理 ---
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 1. 啟動並設定 Service
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_SETUP
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // 2. 【修改】在取得螢幕權限後，立刻開始錄音
            startRecording()

            // 3. 更新狀態
            isSessionActive = true
            updateUiState()

        } else {
            Toast.makeText(this, "未授予螢幕擷取權限", Toast.LENGTH_SHORT).show()
            // 如果使用者拒絕，重設狀態
            isSessionActive = false
            updateUiState()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingService::class.java))
        } else {
            Toast.makeText(this, "懸浮窗權限未授予，無法顯示浮動按鈕", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- 初始化 UI ---
        tvStatus = findViewById(R.id.tvStatus)
        btnControlSession = findViewById(R.id.btn_control_session)

        // --- 註冊廣播接收器 ---
        registerReceivers()

        // --- 請求權限 ---
        requestAllPermissions()
        requestOverlayPermission() // 請求懸浮窗權限

        // --- 設定按鈕點擊事件 ---
        btnControlSession.setOnClickListener {
            if (isSessionActive) {
                stopSessionAndSend()
            } else {
                startSession()
            }
        }
        updateUiState() // 初始化UI
    }

    // 【新增】將註冊邏輯抽出，方便管理
    private fun registerReceivers() {
        val serviceResultFilter = IntentFilter(ScreenCaptureService.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceResultReceiver, serviceResultFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(serviceResultReceiver, serviceResultFilter)
        }

        val sessionToggleFilter = IntentFilter(FloatingService.ACTION_TOGGLE_SESSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionToggleReceiver, sessionToggleFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionToggleReceiver, sessionToggleFilter)
        }
    }

    // 【新增】請求懸浮窗權限的函式
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startService(Intent(this, FloatingService::class.java))
        }
    }

    // 【新增】開始工作階段的函式
    private fun startSession() {
        // 啟動工作階段的第一步是請求螢幕擷取權限
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // 【新增】停止工作階段並傳送的函式
    private fun stopSessionAndSend() {
        stopRecording() // 先停止錄音

        // 檢查錄音檔是否存在
        if (audioFile?.exists() == true) {
            // 命令 Service 進行擷取並傳送
            Intent(this, ScreenCaptureService::class.java).also {
                it.action = ScreenCaptureService.ACTION_CAPTURE_AND_SEND
                it.putExtra(ScreenCaptureService.EXTRA_AUDIO_PATH, audioFile!!.absolutePath)
                startService(it)
            }
            tvStatus.text = "指令已發送，處理中..."
        } else {
            Toast.makeText(this, "找不到錄音檔案，無法傳送", Toast.LENGTH_SHORT).show()
        }

        // 停止螢幕擷取服務
        stopScreenCaptureService()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "請先授予錄音權限", Toast.LENGTH_SHORT).show()
            requestAllPermissions()
            return
        }

        // 修改為 .m4a 格式以獲得更好的相容性
        audioFile = File(cacheDir, "input.m4a")
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(audioFile!!.absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            try {
                prepare()
                start()
                tvStatus.text = "錄音中..."
            } catch (e: IOException) {
                Log.e("MainActivity", "MediaRecorder prepare() failed", e)
                Toast.makeText(this@MainActivity, "錄音裝置啟動失敗", Toast.LENGTH_SHORT).show()
                // 如果錄音失敗，也應該停止整個工作階段
                stopSessionAndSend()
            }
        }
    }

    private fun stopRecording() {
        // 由於 recorder 可能在 startRecording 的 catch 區塊中未被初始化，增加 null 檢查
        recorder?.let {
            try {
                it.stop()
                it.release()
            } catch (e: IllegalStateException) {
                Log.e("MainActivity", "MediaRecorder stop failed", e)
            }
        }
        recorder = null
        tvStatus.text = "錄音結束"
    }

    private fun stopScreenCaptureService() {
        Intent(this, ScreenCaptureService::class.java).also {
            it.action = ScreenCaptureService.ACTION_STOP_SERVICE
            startService(it)
        }
        // 重設狀態
        isSessionActive = false
        updateUiState()
    }

    // 【新增】統一更新 UI 的函式
    private fun updateUiState() {
        // 更新主按鈕
        if (isSessionActive) {
            btnControlSession.text = "停止並傳送"
            tvStatus.text = "螢幕分享與錄音進行中..."
        } else {
            btnControlSession.text = "開始錄音與分享"
            tvStatus.text = "準備就緒"
        }

        // 發送廣播通知 FloatingService 更新狀態
        val updateIntent = Intent(FloatingService.ACTION_UPDATE_STATE).apply {
            putExtra(FloatingService.EXTRA_IS_ACTIVE, isSessionActive)
        }
        sendBroadcast(updateIntent)
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

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceResultReceiver)
        unregisterReceiver(sessionToggleReceiver)
        recorder?.release()
        player?.release()
        stopScreenCaptureService()
    }
}