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
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.view.MotionEvent
import android.widget.ImageView
import com.bumptech.glide.Glide


class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var imageIndex = 0

    // 假設你有五張圖片放在 drawable：ball1 ~ ball5
    private val images = listOf(
        R.drawable.ball1,
        R.drawable.ball2,
        R.raw.gif,
        R.raw.recording,
//        R.drawable.ball4,
//        R.drawable.ball5
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = ImageView(this).apply {
            // 初始背景
//            background = ContextCompat.getDrawable(this@FloatingService, images[imageIndex])

            //直接寫死
            Glide.with(this@FloatingService)
                .asGif()
                .load(R.raw.gif)  // raw/ball1.gif
                .into(this)

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

                        // 判斷是否拖曳
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
                            // Accessibility 建議：performClick
                            v.performClick()

                            // 切換圖片
                            imageIndex = (imageIndex + 1) % images.size

                            val imageRes = images[imageIndex]
                            val typeName = resources.getResourceTypeName(imageRes)

                            if (typeName == "raw") {
                                // raw → 預設當成 GIF 來播
                                Glide.with(this@FloatingService)
                                    .asGif()
                                    .load(imageRes)
                                    .into(floatingView as ImageView)
                            } else {
                                // drawable → 靜態圖
                                Glide.with(this@FloatingService)
                                    .load(imageRes)
                                    .into(floatingView as ImageView)
                            }


                            // 發送廣播
                            val intent = Intent("MOVE_RECT").apply {
                                putExtra("x", 200)
                                putExtra("y", 500)
                            }
                            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                        }
                        true
                    }
                    else -> false
                }
            }


        }



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
    }



    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(floatingView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
