package com.example.gogoge1

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btn = findViewById<Button>(R.id.btnCallApi)
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        btn.setOnClickListener {
            val msg = etMessage.text.toString()
            callApi(msg, tvResult)
        }
    }

    private fun callApi(message: String, tvResult: TextView) {
        val json = JSONObject()
        json.put("message", message)

        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            json.toString()
        )

        // ⚠️ 注意：在 Android 模擬器要用 10.0.2.2 替代 localhost
        val request = Request.Builder()
            .url("http://10.0.2.2:5000/echo")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvResult.text = "Error: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    tvResult.text = "Response: $body"
                }
            }
        })
    }
}
