package com.example.gogoge1

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.graphics.drawable.GradientDrawable


class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var colorIndex = 0
    private val colors = listOf(
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA
    )

    override fun onCreate() {
        super.onCreate()
        Log.d("FloatingService", "Service started")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = View(this).apply {
            // 先建立 GradientDrawable
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors?.get(colorIndex) ?: Color.RED)  // 初始顏色
            }
            background = drawable  // 設為背景

            setOnClickListener {
                //主要要做事的地方，偵測變化
                colorIndex = (colorIndex + 1) % colors.size
                drawable.setColor(colors[colorIndex])  // 更新顏色
            }
        }


        val params = WindowManager.LayoutParams(
            150, 150, // 懸浮球大小
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(floatingView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}