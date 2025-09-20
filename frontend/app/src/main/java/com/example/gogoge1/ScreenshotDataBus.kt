package com.example.gogoge1

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一個單例物件，作為 App 內傳遞截圖數據的中央匯流排。
 * 使用 SharedFlow 來處理一次性的事件。
 */
object ScreenshotDataBus {

    // extraBufferCapacity = 1 確保即使接收端處理較慢，也能緩存一個最新的事件
    private val _screenshots = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 1)
    val screenshots = _screenshots.asSharedFlow()

    /**
     * 從任何地方（例如 Service）發送一個新的截圖數據。
     * tryEmit 是非阻塞的，適用於「發後不理」的事件。
     */
    fun postScreenshot(data: ByteArray) {
        _screenshots.tryEmit(data)
    }
}
