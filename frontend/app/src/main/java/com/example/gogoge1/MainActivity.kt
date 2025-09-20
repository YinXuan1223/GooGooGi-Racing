package com.example.gogoge1

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.graphics.Color

enum class RecordingState {
    IDLE,
    RECORDING,
    LOADING,
    SUCCESS,
    ERROR
}

class MainActivity : AppCompatActivity() {
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var currentState: RecordingState = RecordingState.IDLE

    private val client = OkHttpClient.Builder()
        .connectTimeout(100, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .writeTimeout(timeout = 100, TimeUnit.SECONDS)
        .build()
    private lateinit var tvStatus: TextView
    private lateinit var btnStartBubble: Button

    private val bubbleClickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.gogoge1.BUBBLE_CLICK") {
                when (currentState) {
                    RecordingState.IDLE, RecordingState.SUCCESS, RecordingState.ERROR -> {
                        startRecording()
                    }
                    RecordingState.RECORDING -> {
                        stopRecording()
                        audioFile?.let { sendToServer(it) }
                    }
                    else -> {
                        // Do nothing while in LOADING state
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        btnStartBubble = findViewById(R.id.btnStartBubble)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        }

        btnStartBubble.setOnClickListener {
            checkOverlayPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.example.gogoge1.BUBBLE_CLICK")
        registerReceiver(bubbleClickReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(bubbleClickReceiver)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 201)
            Toast.makeText(this, "請允許應用程式在其他應用程式上層顯示", Toast.LENGTH_LONG).show()
        } else {
            startFloatingBubbleService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingBubbleService()
            } else {
                Toast.makeText(this, "權限被拒絕，無法啟動浮動按鈕", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startFloatingBubbleService() {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java)
        startService(serviceIntent)
        Toast.makeText(this, "浮動按鈕已啟動", Toast.LENGTH_SHORT).show()
    }

    private fun startRecording() {
        audioFile = File(cacheDir, "input.mp4") // Change extension to mp4
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4) // Use MPEG_4 container
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // Use AAC encoder
            setOutputFile(audioFile!!.absolutePath)
            prepare()
            start()
        }
        updateStatus(RecordingState.RECORDING, "Recording...")
    }

    private fun stopRecording() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
        updateStatus(RecordingState.IDLE, "Recording stopped")
    }

    private fun sendToServer(file: File) {
        updateStatus(RecordingState.LOADING, "Sending to server...")
        updateStatus(RecordingState.LOADING, "Sending to server...")
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "input.mp4", // Change filename to .mp4
                file.asRequestBody("audio/mp4".toMediaTypeOrNull()) // Change MIME type to audio/mp4
            )
            .build()

        val request = Request.Builder()
            .url("http://172.18.87.117:5000/voice")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    updateStatus(RecordingState.ERROR, "Error: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val audioBytes = response.body?.bytes()
                if (audioBytes != null) {
                    val outFile = File(cacheDir, "reply.mp3")
                    FileOutputStream(outFile).use { it.write(audioBytes) }

                    runOnUiThread {
                        updateStatus(RecordingState.SUCCESS, "Received reply, playing...")
                        MediaPlayer().apply {
                            setDataSource(outFile.absolutePath)
                            prepare()
                            start()
                        }
                    }
                } else {
                    runOnUiThread {
                        updateStatus(RecordingState.ERROR, "Error: No audio data received.")
                    }
                }
            }
        })
    }

    private fun updateStatus(state: RecordingState, message: String) {
        currentState = state
        tvStatus.text = message

        val color = when (state) {
            RecordingState.IDLE -> Color.parseColor("#4CAF50")
            RecordingState.RECORDING -> Color.parseColor("#F44336")
            RecordingState.LOADING -> Color.parseColor("#FFEB3B")
            RecordingState.SUCCESS -> Color.parseColor("#2196F3")
            RecordingState.ERROR -> Color.parseColor("#9E9E9E")
        }

        val colorIntent = Intent("com.example.gogoge1.UPDATE_BUBBLE_COLOR")
        colorIntent.putExtra("color", color)
        sendBroadcast(colorIntent)
    }
}