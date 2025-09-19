package com.example.gogoge1

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private lateinit var btnStartService: Button
    private lateinit var btnToggleCapture: Button
    private var isCapturing = false

    // --- 新增：用來追蹤服務是否正在運行的狀態旗標 ---
    private var isServiceRunning = false

    // 用於接收「螢幕擷取」權限結果的 Launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("ScreenCaptureDebug", "螢幕擷取權限已授予")
            val data: Intent? = result.data
            if (data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)

                // --- 修改：更新服務狀態並刷新UI ---
                isServiceRunning = true
                updateUiState()
            }
        } else {
            Log.w("ScreenCaptureDebug", "螢幕擷取權限被拒絕")
            Toast.makeText(this, "需要螢幕擷取權限才能運作", Toast.LENGTH_SHORT).show()
        }
    }

    // 用於接收「通知」權限結果的 Launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("ScreenCaptureDebug", "通知權限已授予，準備請求螢幕擷取")
            requestMediaProjection()
        } else {
            Log.w("ScreenCaptureDebug", "通知權限被拒絕")
            Toast.makeText(this, "需要通知權限以顯示服務狀態", Toast.LENGTH_SHORT).show()
            requestMediaProjection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartService = findViewById(R.id.btn_start_service)
        btnToggleCapture = findViewById(R.id.btn_toggle_capture)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // --- 修改：「啟動/停止服務」按鈕的點擊事件 ---
        btnStartService.setOnClickListener {
            if (isServiceRunning) {
                // 如果服務正在運行，就發送停止服務的指令
                stopScreenCaptureService()
            } else {
                // 如果服務未運行，就開始權限請求流程
                requestNotificationPermission()
            }
        }

        btnToggleCapture.setOnClickListener {
            val action = if (isCapturing) {
                ScreenCaptureService.ACTION_STOP_CONTINUOUS_CAPTURE
            } else {
                ScreenCaptureService.ACTION_START_CONTINUOUS_CAPTURE
            }
            val intent = Intent(this, ScreenCaptureService::class.java).apply { this.action = action }
            startService(intent)
            isCapturing = !isCapturing
            updateButtonText()
        }

        // --- 新增：初始化UI狀態 ---
        updateUiState()
    }

    // --- 新增：停止服務的函式 ---
    private fun stopScreenCaptureService() {
        Log.d("ScreenCaptureDebug", "發送停止服務的指令...")
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_SERVICE
        }
        startService(intent)

        // 更新服務狀態並刷新UI
        isServiceRunning = false
        updateUiState()
    }

    // --- 新增：一個統一管理UI狀態的函式 ---
    private fun updateUiState() {
        if (isServiceRunning) {
            btnStartService.text = "停止截圖服務"
            btnToggleCapture.isEnabled = true
        } else {
            btnStartService.text = "啟動截圖服務"
            btnToggleCapture.isEnabled = false
            // 當服務停止時，重置連續截圖的狀態和按鈕文字
            isCapturing = false
            updateButtonText()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                requestMediaProjection()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun updateButtonText() {
        btnToggleCapture.text = if (isCapturing) "暫停連續截圖" else "開始連續截圖"
    }
}