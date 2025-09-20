package com.example.gogoge1

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager


class HighlightService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rectView: View
    private lateinit var params: WindowManager.LayoutParams

    private val moveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MOVE_RECT") {
                val x = intent.getIntExtra("x", params.x)
                val y = intent.getIntExtra("y", params.y)
                updateRect(x, y , 300 , 150)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 建立中空矩形
        rectView = View(this).apply {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(5, Color.RED)
                setColor(Color.TRANSPARENT)
            }
            background = drawable
        }

        // 設定初始位置與大小
        params = WindowManager.LayoutParams(
            300, 150, // 寬高
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        windowManager.addView(rectView, params)
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(moveReceiver, IntentFilter("MOVE_RECT"))


    }

    // 變透明
    fun hideRect() {
        rectView.alpha = 0f
    }

    // 顯示
    fun showRect() {
        rectView.alpha = 1f
    }

    // 動態移動或調整大小
    fun updateRect(x: Int, y: Int, width: Int, height: Int) {
        params.x = x
        params.y = y
        params.width = width
        params.height = height
        windowManager.updateViewLayout(rectView, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            "SHOW_RECT" -> rectView.alpha = 1f
            "HIDE_RECT" -> rectView.alpha = 0f
        }
        return START_STICKY
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(moveReceiver)
        windowManager.removeView(rectView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
