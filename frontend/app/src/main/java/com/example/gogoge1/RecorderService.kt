package com.example.gogoge1

import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Observer
import java.io.File

class RecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private lateinit var app: MyApp

    // 監控 globalState
    private val stateObserver = Observer<Int> { state ->
        when (state) {
            1 -> startRecording()
            2 -> stopRecording()
            else -> {
                // 可加入其他狀態行為
                Log.d("RecorderService", "State changed: $state")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as MyApp

        // 開始觀察 globalState
        app.globalState.observeForever(stateObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止觀察，避免記憶體洩漏
        app.globalState.removeObserver(stateObserver)
        stopRecording()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        if (recorder != null) return // 已經在錄音

        audioFile = File(cacheDir, "input_${System.currentTimeMillis()}.wav")

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setOutputFile(audioFile!!.absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            prepare()
            start()
        }

        Log.d("RecorderService", "Recording started: ${audioFile!!.absolutePath}")
    }

    private fun stopRecording() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null

        Log.d("RecorderService", "Recording stopped")
    }

    // 如果需要，提供外部方法取得最近錄音檔
    fun getLastRecordingFile(): File? = audioFile
}
