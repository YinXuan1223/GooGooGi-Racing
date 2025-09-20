package com.example.gogoge1

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var currentState: AppState = AppState.IDLE

    // 定義狀態對應的圖片
    private val idleImage = R.drawable.idle
    private val recordingImage = R.drawable.recording
    private val thinkingImage = R.drawable.thinking
    private val responseImage = R.drawable.answer
    private val successImage = R.drawable.success
    private val errorImage = R.drawable.error
    // 【附註】請確保 R.drawable.recording 和 R.drawable.thinking 圖檔已存在於您的專案中

    companion object {
        const val ACTION_TOGGLE_SESSION = "com.example.gogoge1.ACTION_TOGGLE_SESSION"
        const val ACTION_UPDATE_STATE = "com.example.gogoge1.ACTION_UPDATE_STATE"
        const val EXTRA_APP_STATE = "EXTRA_APP_STATE"
    }

    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_STATE) {
                val stateName = intent.getStringExtra(EXTRA_APP_STATE)
                currentState = try {
                    // 安全地將字串轉換回 AppState 枚舉
                    AppState.valueOf(stateName ?: "IDLE")
                } catch (e: IllegalArgumentException) {
                    // 如果收到未知的狀態名稱，則預設為 IDLE，避免崩潰
                    AppState.IDLE
                }
                updateFloatingViewIcon()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = createFloatingView()

        val params = WindowManager.LayoutParams(
            300, 300,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        windowManager.addView(floatingView, params)

        val intentFilter = IntentFilter(ACTION_UPDATE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateUpdateReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(stateUpdateReceiver, intentFilter)
        }
    }

    private fun createFloatingView(): View {
        return View(this).apply {
            background = ContextCompat.getDrawable(this@FloatingService, idleImage)

            // --- 拖曳與點擊邏輯 (保持不變) ---
            var initialX = 0
            var initialY = 0
            var touchStartX = 0f
            var touchStartY = 0f
            var isDragging = false

            setOnTouchListener { v, event ->
                val params = v.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchStartX).toInt()
                        val dy = (event.rawY - touchStartY).toInt()
                        if (!isDragging && (dx * dx + dy * dy) > 25) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(v, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            v.performClick()
                            sendBroadcast(Intent(ACTION_TOGGLE_SESSION))
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun updateFloatingViewIcon() {
        val newIconRes = when (currentState) {
            AppState.IDLE -> idleImage
            AppState.RECORDING -> recordingImage
            AppState.THINKING -> thinkingImage
            AppState.RESPONSE -> responseImage
            AppState.SUCCESS -> successImage
            AppState.ERROR -> errorImage
        }
        floatingView.background = ContextCompat.getDrawable(this, newIconRes)
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(floatingView)
        unregisterReceiver(stateUpdateReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}