package com.quickcall.macro

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * 매크로 시작/정지 로직 중앙 집중화.
 *
 * 호출 진입점:
 *  - MainActivity 의 시작/정지 버튼
 *  - OverlayService 의 토글 (ON/OFF)
 *
 * 진입 검증 규칙:
 *  - 권한 부여 ≠ 매크로 시작.
 *  - 명시적 "시작" 액션이 있어야만 동작 시작.
 *  - 권한 미비면 토스트 + 권한 화면 안내, 매크로 시작 안 함.
 *  - MediaProjection 캡처는 매크로 시작 시에만 1회 요청, 정지 시 같이 종료.
 *
 * MediaProjection 다이얼로그는 Activity 컨텍스트가 필요해서,
 * Service(오버레이)에서 시작 트리거 시엔 MainActivity 를 띄워서 거기서 처리한다.
 */
object MacroController {

    /** 화면 활성 상태에서 호출. true 반환 = 매크로 ON 상태(이미였든 새로 켰든). */
    fun tryStartFromActivity(activity: MainActivity): Boolean {
        if (!ensurePermissionsOrPrompt(activity)) return false

        if (PreferencesManager.enabled) {
            // 이미 동작 중이면 충돌 방지 토스트만
            if (!ScreenCaptureService.isRunning) {
                // 이전 세션 잔재 가능성 — 캡처 다시 요청
                activity.requestProjectionDialog()
                return true
            }
            Toast.makeText(activity, R.string.toast_already_running, Toast.LENGTH_SHORT).show()
            return true
        }

        // 캡처 권한이 없으면 다이얼로그 1회 — 결과는 MainActivity 의 launcher 가 처리해서
        // enabled=true 와 오버레이/포그라운드 시작까지 끌고 간다.
        if (!ScreenCaptureService.isRunning) {
            activity.requestProjectionDialog()
        } else {
            // 캡처가 이미 켜져있는 비정상 케이스 → 그대로 enabled 만 true
            finalizeStart(activity)
        }
        return true
    }

    /**
     * Service(오버레이) 등 Activity 가 아닌 곳에서 시작을 시도할 때.
     * 권한 다이얼로그/프로젝션 요청이 필요한 경우 MainActivity 를 띄움.
     */
    fun tryStartFromBackground(ctx: Context) {
        // 권한 미비 상태에서 그냥 시작 못 함 → 액티비티 열어서 사용자가 처리
        if (!hasAccessibility(ctx) || !hasOverlay(ctx) || !ScreenCaptureService.isRunning) {
            val i = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_REQUEST_START, true)
            }
            ctx.startActivity(i)
            return
        }

        if (PreferencesManager.enabled) {
            Toast.makeText(ctx, R.string.toast_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        finalizeStart(ctx)
    }

    /**
     * MainActivity 에서 MediaProjection 결과 처리 후 호출하는 진입점.
     * 캡처 권한 거절돼도 매크로는 시작 가능 (이미지 폴백만 비활성).
     */
    fun finalizeStart(ctx: Context) {
        PreferencesManager.enabled = true
        if (Settings.canDrawOverlays(ctx) && !OverlayService.isRunning) {
            ctx.startService(Intent(ctx, OverlayService::class.java))
        }
        Toast.makeText(ctx, R.string.toast_macro_started, Toast.LENGTH_SHORT).show()
    }

    fun stop(ctx: Context) {
        val wasOn = PreferencesManager.enabled
        PreferencesManager.enabled = false
        // 진행 중 시퀀스 즉시 중단
        CallMacroService.instance?.cancelSequence()
        // 캡처 서비스도 정지 (MediaProjection 자원 반환)
        if (ScreenCaptureService.isRunning) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
        if (wasOn) {
            Toast.makeText(ctx, R.string.toast_macro_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    // ─── 권한 검증 ───────────────────────────────────────

    private fun ensurePermissionsOrPrompt(activity: MainActivity): Boolean {
        if (!hasAccessibility(activity)) {
            Toast.makeText(activity, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {}
            return false
        }
        if (!hasOverlay(activity)) {
            Toast.makeText(activity, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (_: Throwable) {}
            return false
        }
        return true
    }

    fun hasAccessibility(ctx: Context): Boolean {
        return MainActivity.isAccessibilityEnabledStatic(ctx)
    }

    fun hasOverlay(ctx: Context): Boolean {
        return Settings.canDrawOverlays(ctx)
    }

    @Suppress("unused")
    private fun isOreoOrLater() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
