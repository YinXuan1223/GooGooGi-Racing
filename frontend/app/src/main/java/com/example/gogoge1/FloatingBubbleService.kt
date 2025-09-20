package com.example.gogoge1

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingBubbleView: View
    private lateinit var bubbleButton: Button

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.gogoge1.UPDATE_BUBBLE_COLOR") {
                val color = intent.getIntExtra("color", Color.parseColor("#4CAF50"))
                updateBubbleColor(color)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter("com.example.gogoge1.UPDATE_BUBBLE_COLOR")
        registerReceiver(colorReceiver, filter)

        floatingBubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble_layout, null)
        bubbleButton = floatingBubbleView.findViewById(R.id.bubbleButton)
        updateBubbleColor(Color.parseColor("#4CAF50"))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingBubbleView, params)

        floatingBubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(event.rawX - initialTouchX) < 10 && kotlin.math.abs(event.rawY - initialTouchY) < 10) {
                            val intent = Intent("com.example.gogoge1.BUBBLE_CLICK")
                            sendBroadcast(intent)
                            return true
                        }
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingBubbleView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    fun updateBubbleColor(color: Int) {
        bubbleButton.setBackgroundColor(color)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(colorReceiver)
        if (::floatingBubbleView.isInitialized) {
            windowManager.removeView(floatingBubbleView)
        }
    }
}