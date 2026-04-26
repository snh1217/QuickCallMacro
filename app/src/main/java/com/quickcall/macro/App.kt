package com.quickcall.macro

import android.app.Application
import com.quickcall.macro.data.DistrictRepository

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
        // 안전장치: 앱 콜드 스타트 시 매크로는 항상 OFF 로 시작.
        // 폰 재부팅 후 MediaProjection 토큰이 무효화된 상태에서 자동 동작하는 것을 방지.
        PreferencesManager.enabled = false
        // 행정구역 데이터 사전 로드 + v1.0.4 → v1.0.5 마이그레이션 (백그라운드)
        Thread {
            try {
                DistrictRepository.ensureLoaded(this)
                if (!PreferencesManager.migrationV105Done) {
                    PreferencesManager.migrateV104ToV105 { sigunguPath ->
                        DistrictRepository.getDongsForSigungu(sigunguPath)
                    }
                }
            } catch (_: Throwable) {}
        }.start()
    }
}
