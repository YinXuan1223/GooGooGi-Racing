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
    private var isSessionActive = false // 用於追蹤目前狀態

    // 定義狀態對應的圖片
    private val activeImage = R.drawable.ball1  // 執行中狀態
    private val inactiveImage = R.drawable.ball2 // 閒置狀態

    companion object {
        // 【新增】定義廣播 Actions，避免字串寫死
        const val ACTION_TOGGLE_SESSION = "com.example.gogoge1.ACTION_TOGGLE_SESSION"
        const val ACTION_UPDATE_STATE = "com.example.gogoge1.ACTION_UPDATE_STATE"
        const val EXTRA_IS_ACTIVE = "EXTRA_IS_ACTIVE"
    }

    // 【新增】廣播接收器，用來接收來自 MainActivity 的狀態更新
    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_STATE) {
                isSessionActive = intent.getBooleanExtra(EXTRA_IS_ACTIVE, false)
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
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)

        // 【新增】註冊廣播接收器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateUpdateReceiver, IntentFilter(ACTION_UPDATE_STATE), RECEIVER_EXPORTED)
        } else {
            registerReceiver(stateUpdateReceiver, IntentFilter(ACTION_UPDATE_STATE))
        }
    }

    private fun createFloatingView(): View {
        return View(this).apply {
            // 初始背景設為閒置狀態
            background = ContextCompat.getDrawable(this@FloatingService, inactiveImage)

            // --- 拖曳邏輯 (保持不變) ---
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
                        if (!isDragging && (dx * dx + dy * dy) > 25) { // 判斷為拖曳
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
                            // 【修改】點擊時，發送廣播通知 MainActivity
                            sendBroadcast(Intent(ACTION_TOGGLE_SESSION))
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // 【新增】更新圖示的函式
    private fun updateFloatingViewIcon() {
        val newIcon = if (isSessionActive) activeImage else inactiveImage
        floatingView.background = ContextCompat.getDrawable(this, newIcon)
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(floatingView)
        // 【新增】取消註冊廣播接收器
        unregisterReceiver(stateUpdateReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}