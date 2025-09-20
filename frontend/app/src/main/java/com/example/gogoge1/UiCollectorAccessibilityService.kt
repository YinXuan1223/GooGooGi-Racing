package com.example.gogoge1

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class UiCollectorAccessibilityService : AccessibilityService() {
    private val TAG = "UiCollectorAS"
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor()

    // 開發時：預設使用 emulator host (10.0.2.2). 真機請改成你 server IP。
    private var serverUrl = "http://10.0.2.2:5000/ui"

    // 簡單 throttle，避免事件太多
    private var lastSent = 0L
    private val THROTTLE_MS = 1500L



    override fun onServiceConnected() {

        Log.d(TAG, "Service connected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val app = application as MyApp
        Log.d("UiCollector", "globalState = ${app.globalState}")
        Log.d(TAG, "Event triggered: ${event?.eventType}")
        val now = System.currentTimeMillis()
        if (now - lastSent < THROTTLE_MS) return
        if(app.globalState.value == 0)return;
        lastSent = now

        val root = rootInActiveWindow ?: return
        val payload = JSONObject()
        payload.put("eventType", event?.eventType)
        payload.put("package", event?.packageName ?: JSONObject.NULL)

        val nodes = JSONArray()
        traverseNode(root, nodes)
        payload.put("nodes", nodes)

        Log.d(TAG, "Captured ${nodes.length()} nodes; sending to server")
        sendJson(payload.toString())
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, arr: JSONArray) {
        if (node == null) return
        try {
            val obj = JSONObject()
            obj.put("className", node.className?.toString() ?: JSONObject.NULL)
            obj.put("text", node.text?.toString() ?: JSONObject.NULL)
            obj.put("contentDescription", node.contentDescription?.toString() ?: JSONObject.NULL)
            obj.put("viewId", node.viewIdResourceName ?: JSONObject.NULL)
            val r = Rect()
            node.getBoundsInScreen(r)
            val b = JSONObject()
            b.put("l", r.left); b.put("t", r.top); b.put("r", r.right); b.put("b", r.bottom)
            obj.put("bounds", b)
            arr.put(obj)

            // 迭代 children
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), arr)
            }
        } catch (e: Exception) {
            Log.w(TAG, "traverseNode exception: ${e.localizedMessage}")
        }
    }


    private fun sendJson(payload: String) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toRequestBody(mediaType)

        val req = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .build()

        executor.execute {
            try {
                val resp = client.newCall(req).execute()
                Log.d(TAG, "Server responded: ${resp.code}")
                resp.close()
            } catch (e: Exception) {
                Log.e(TAG, "Send failed: ${e.localizedMessage}")
            }
        }
    }


    override fun onInterrupt() { Log.d(TAG, "Interrupted") }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
