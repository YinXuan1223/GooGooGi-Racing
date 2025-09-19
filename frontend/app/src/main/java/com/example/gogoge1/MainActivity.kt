package com.example.gogoge1

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var btnControlCapture: Button

    private var isServiceRunning = false

    // 用於接收「螢幕擷取」權限結果的 Launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("ScreenCaptureDebug", "螢幕擷取權限已授予")
            val data: Intent? = result.data
            if (data != null) {
                // 1. 啟動前景服務，傳入權限資料
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)

                // 2. 立刻發送「開始連續截圖」的指令
                val startCaptureIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START_CONTINUOUS_CAPTURE
                }
                startService(startCaptureIntent)

                // 3. 更新 UI 狀態
                isServiceRunning = true
                updateUiState()
            }
        } else {
            Log.w("ScreenCaptureDebug", "螢幕擷取權限被拒絕")
            isServiceRunning = false // 確保狀態正確
            updateUiState()
            Toast.makeText(this, "需要螢幕擷取權限才能運作", Toast.LENGTH_SHORT).show()
        }
    }

    // 用於接收「通知」權限結果的 Launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "需要通知權限以顯示服務狀態", Toast.LENGTH_SHORT).show()
            requestMediaProjection()
        }
    }

    // 用於接收「懸浮窗」權限結果的 Launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 當使用者從設定頁面回來後
        if (Settings.canDrawOverlays(this)) {
            Log.d("OverlayPermission", "懸浮窗權限已授予")
            startService(Intent(this, FloatingService::class.java))
        } else {
            Log.w("OverlayPermission", "懸浮窗權限被拒絕")
            Toast.makeText(this, "需要懸浮窗權限才能顯示浮動按鈕", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnControlCapture = findViewById(R.id.btn_control_capture)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 呼叫懸浮窗權限檢查
        requestOverlayPermission()

        // ✅ 修正後的單一按鈕點擊事件，純粹的開/關邏輯
        btnControlCapture.setOnClickListener {
            if (isServiceRunning) {
                // 情況 1: 服務正在運行 -> 發送停止服務的指令
                val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP_SERVICE
                }
                startService(stopIntent)
                isServiceRunning = false
                updateUiState()
                Toast.makeText(this, "已停止分享與截圖", Toast.LENGTH_SHORT).show()
            } else {
                // 情況 2: 服務未運行 -> 走權限申請流程
                requestNotificationPermission()
            }
        }
        updateUiState()
    }

    // 請求懸浮窗權限的函式
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // 跳轉到授權頁面
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            // 已授權，直接啟動浮動球
            startService(Intent(this, FloatingService::class.java))
        }
    }


    private fun updateUiState() {
        btnControlCapture.text = if (isServiceRunning) "停止分享與截圖" else "啟動分享與截圖"
    }

    // 權限請求相關函式保持不變
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
}