package com.quickcall.macro

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
        // 안전장치: 앱 콜드 스타트 시 매크로는 항상 OFF 로 시작.
        // 폰 재부팅 후 MediaProjection 토큰이 무효화된 상태에서 자동 동작하는 것을 방지.
        PreferencesManager.enabled = false
    }
}
