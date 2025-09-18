package com.example.gogoge1

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private val client = OkHttpClient()
    private lateinit var tvStatus: TextView

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
        val btnRecord = findViewById<Button>(R.id.btnRecord)
        val btnSend = findViewById<Button>(R.id.btnSend)

        // 請求權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        }

        btnRecord.setOnClickListener {
            if (recorder == null) startRecording() else stopRecording()
        }

        btnSend.setOnClickListener {
            audioFile?.let { sendToServer(it) }
        }
    }

    private fun startRecording() {
        audioFile = File(cacheDir, "input.wav")
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP) // or MPEG_4
            setOutputFile(audioFile!!.absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            prepare()
            start()
        }
        tvStatus.text = "Recording..."
    }

    private fun stopRecording() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
        tvStatus.text = "Recording stopped"
    }

    private fun sendToServer(file: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "input.wav",
                file.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("http://192.168.50.184:5000/voice") // 模擬器用 10.0.2.2
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { tvStatus.text = "Error: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val audioBytes = response.body?.bytes()
                if (audioBytes != null) {
                    val outFile = File(cacheDir, "reply.mp3")
                    FileOutputStream(outFile).use { it.write(audioBytes) }

                    runOnUiThread {
                        tvStatus.text = "Received reply, playing..."
                        MediaPlayer().apply {
                            setDataSource(outFile.absolutePath)
                            prepare()
                            start()
                        }
                    }
                }
            }
        })
    }
}
