package com.example.gogoge1

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibility"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode: AccessibilityNodeInfo? = rootInActiveWindow
        if (rootNode != null) {
            printNode(rootNode, 0)
        }
        else{
            Log.d(TAG, "rootNode is null")
        }
    }

    private fun printNode(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return

        val indent = "  ".repeat(depth)
        val text = node.text
        val desc = node.contentDescription

        Log.d(TAG, "$indent Class: ${node.className} | Text: $text | Desc: $desc")

        for (i in 0 until node.childCount) {
            printNode(node.getChild(i), depth + 1)
        }
    }

    override fun onInterrupt() {
        // 無障礙服務被系統中斷時呼叫
    }
}
