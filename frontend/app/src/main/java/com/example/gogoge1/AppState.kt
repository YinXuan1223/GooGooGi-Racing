package com.example.gogoge1

/**
 * 定義整個應用程式的共享狀態
 * 為了讓 MainActivity 和 FloatingService 都能共用
 */
enum class AppState {
    IDLE,       // 1. 閒置狀態：服務未啟動
    RECORDING,  // 2. 錄音狀態：正在錄音並擷取螢幕
    THINKING,   // 3. 思考狀態：音檔已傳送，等待 API 回應
    RESPONSE,   // 4. 回應狀態：正在播放 API 回傳的音檔
    SUCCESS,    // 5. 成功狀態：任務完成 (mission_achieved = true)
    ERROR       // 6. 錯誤狀態：發生任何錯誤
}