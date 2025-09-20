package com.example.gogoge1

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream


class MainActivity : AppCompatActivity() {

    // --- UI 元件 ---
    private lateinit var tvStatus: TextView
    private lateinit var btnControlSession: Button

    // --- 狀態與功能變數 ---
    private var currentState: AppState = AppState.IDLE
    private var audioFile: File? = null
    private var player: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private val TIMEOUT_MS = 50000L
    private var resetStateRunnable: Runnable? = null
    // --- 廣播接收器 (保持不變) ---
    private val serviceResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            cancelTimeout()
            val error = intent?.getStringExtra(ScreenCaptureService.EXTRA_ERROR)
            if (error != null) {
                tvStatus.text = "錯誤: $error"
                Toast.makeText(this@MainActivity, "錯誤: $error", Toast.LENGTH_LONG).show()
                // 發生錯誤時，無論如何都結束工作階段
                updateState(AppState.ERROR)
                return
            }

            // 提取所有回傳資料
            val status = intent?.getStringExtra(ScreenCaptureService.EXTRA_STATUS) ?: "未知狀態"
            val audioBase64 = intent?.getStringExtra(ScreenCaptureService.EXTRA_AUDIO_BASE64)
            // 假設 Service 會傳回 mission_achieved 狀態
            val missionAchieved = intent?.getBooleanExtra("MISSION_ACHIEVED", false) ?: false


            if (missionAchieved) {
                updateState(AppState.SUCCESS)
                if (audioBase64 != null) {
                    playBase64Audio(audioBase64) // 播放最後的成功音訊
                }
            } else {
                updateState(AppState.RESPONSE)
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

            // 2. 在取得螢幕權限後，立刻開始錄音
            startRecording()
        } else {
            Toast.makeText(this, "未授予螢幕擷取權限", Toast.LENGTH_SHORT).show()
            updateState(AppState.ERROR) // 權限失敗視為錯誤，回到閒置
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
            when (currentState) {
                AppState.IDLE, AppState.RESPONSE, AppState.SUCCESS, AppState.ERROR -> {
                    // 從任何「已停止」的狀態，都可以開始一個新階段
                    startSession()
                }
                AppState.RECORDING -> {
                    // 正在錄音時，按鈕功能是「停止並傳送」
                    stopAndSend()
                }
                AppState.THINKING -> {
                    // 思考中，按鈕應為禁用狀態，不執行任何操作
                }
            }
        }
        updateState(AppState.IDLE) // 初始化 UI 狀態
    }
    private fun updateState(newState: AppState) {
        if (currentState == newState) return // 狀態相同則不重複執行
        currentState = newState
        Log.d("MainActivity", "State changed to: $newState")

        cancelResetState()

        when (newState) {
            AppState.IDLE -> {
                btnControlSession.text = "開始錄音與分享"
                tvStatus.text = "準備就緒"
                btnControlSession.isEnabled = true
            }
            AppState.RECORDING -> {
                player?.release() // 只要進入錄音狀態，就立刻停止播放音檔
                player = null
                btnControlSession.text = "停止並傳送"
                tvStatus.text = "錄音中，請說話..."
                btnControlSession.isEnabled = true
            }
            AppState.THINKING -> {
                btnControlSession.isEnabled = false // 思考中，禁用按鈕防止重複點擊
                tvStatus.text = "指令已發送，AI 思考中..."
                startTimeout()
            }
            AppState.RESPONSE -> {
                btnControlSession.text = "開始新對話" // 播放完畢後，可以再次錄音
                btnControlSession.isEnabled = false
            }
            AppState.SUCCESS -> {
                tvStatus.append("\n✨ 任務完成！")
                btnControlSession.text = "開始新任務"
                btnControlSession.isEnabled = true
                // 延遲 3 秒後執行 cleanupSession，它會將狀態切換到 IDLE
                resetStateRunnable = Runnable { cleanupSession() }
                handler.postDelayed(resetStateRunnable!!, 3000L)
            }
            AppState.ERROR -> {
                tvStatus.append("\n❌ 發生錯誤")
                btnControlSession.text = "重試"
                btnControlSession.isEnabled = true
                // 延遲 3 秒後執行 cleanupSession
                resetStateRunnable = Runnable { cleanupSession() }
                handler.postDelayed(resetStateRunnable!!, 3000L)
            }
        }

        // 發送廣播通知 FloatingService 更新狀態
        val updateIntent = Intent(FloatingService.ACTION_UPDATE_STATE).apply {
            putExtra(FloatingService.EXTRA_APP_STATE, newState.name) // 傳送狀態的名稱
        }
        sendBroadcast(updateIntent)
    }

    private fun startSession() {
        cleanupSession()
        updateState(AppState.RECORDING) // 狀態轉換為錄音中
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopAndSend() {
        stopRecording()

        // 2. 延遲一小段時間 (例如 100ms)，確保錄音檔已完全寫入
        handler.postDelayed({
            // 3. 延遲後，再發送「開始監控」指令
            if (audioFile?.exists() == true) {
                updateState(AppState.THINKING) // 狀態轉換為思考中
                Intent(this, ScreenCaptureService::class.java).also {
                    it.action = ScreenCaptureService.ACTION_START_MONITORING
                    it.putExtra(ScreenCaptureService.EXTRA_AUDIO_PATH, audioFile!!.absolutePath)
                    startService(it)
                }
            } else {
                Toast.makeText(this, "找不到錄音檔案，無法傳送", Toast.LENGTH_SHORT).show()
                updateState(AppState.ERROR)
            }
        }, 100)
    }

    private fun cleanupSession() {
        Log.d("MainActivity", "正在清理工作階段資源...")
        cancelResetState()
        stopService(Intent(this, ScreenCaptureService::class.java))
        audioFile?.delete()
        audioFile = null
        player?.release()
        player = null
        // 只有在目前狀態不是 IDLE 時才更新，避免重複觸發
        if (currentState != AppState.IDLE) {
            updateState(AppState.IDLE)
        }
    }
    private fun startTimeout() {
        cancelTimeout() // 先取消舊的，確保只有一個在跑
        timeoutRunnable = Runnable {
            Log.e("MainActivity", "服務回應超時！")
            Toast.makeText(this, "錯誤：服務回應超時", Toast.LENGTH_LONG).show()
            updateState(AppState.ERROR)
        }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun cancelResetState() {
        resetStateRunnable?.let { handler.removeCallbacks(it) }
        resetStateRunnable = null
    }

    // 將註冊邏輯抽出，方便管理
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

    private fun startRecording() {
        // 檢查權限的邏輯仍然可以保留
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "請先授予錄音權限", Toast.LENGTH_SHORT).show()
            requestAllPermissions()
            return
        }

        // 建立錄音檔案的路徑，並傳給 Service
        audioFile = File(cacheDir, "input.m4a")

        // 透過 Intent 向 Service 發送開始錄音的指令
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_RECORDING // 【重要】需要在 Service 中定義這個新的 Action
            putExtra(ScreenCaptureService.EXTRA_AUDIO_PATH, audioFile!!.absolutePath)
        }

        // 必須使用 startForegroundService
        ContextCompat.startForegroundService(this, serviceIntent)

        // 更新 UI 狀態
        updateState(AppState.RECORDING)
    }

    // stopRecording 也是發送指令
    private fun stopRecording() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_RECORDING // 【重要】需要在 Service 中定義這個新的 Action
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        tvStatus.text = "錄音結束"
    }

    private fun stopScreenCaptureService() {
        Intent(this, ScreenCaptureService::class.java).also {
            it.action = ScreenCaptureService.ACTION_STOP_SERVICE
            startService(it)
        }
    }



    private fun playBase64Audio(base64Audio: String) {
        if (currentState == AppState.RECORDING) {
            Log.d("MainActivity", "正在錄音，已略過本次音檔播放。")
            return
        }
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tempMp3 = File.createTempFile("reply", "mp3", cacheDir)
            FileOutputStream(tempMp3).use { it.write(audioBytes) }

            player?.release()
            player = MediaPlayer().apply {
                setDataSource(tempMp3.absolutePath)
                prepareAsync()
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    tvStatus.text = "播放完畢"
                    it.release()
                    tempMp3.delete()
                    // 播放完畢後，如果任務還沒成功，可以停留在 RESPONSE 狀態
                    // 如果任務已成功，則已經在 SUCCESS 狀態
                }
                setOnErrorListener { _, _, _ ->
                    tvStatus.text = "播放失敗"
                    release()
                    tempMp3.delete()
                    updateState(AppState.ERROR)
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "播放Base64音訊時出錯", e)
            tvStatus.text = "播放音訊時出錯"
            updateState(AppState.ERROR)
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
        player?.release()
        stopScreenCaptureService()
    }
}