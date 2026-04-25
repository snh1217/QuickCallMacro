package com.quickcall.macro

/**
 * AccessibilityService(노드 매칭 실패) → ScreenCaptureService(이미지 매칭) 로
 * 매칭 요청을 전달하는 가벼운 브리지.
 *
 * - ScreenCaptureService 가 켜져 있을 때만 콜백이 등록됨
 * - 켜져 있지 않으면 요청은 그냥 무시됨 (앱 단순화)
 */
object ImageMatchBridge {
    @Volatile
    private var listener: (() -> Unit)? = null

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    fun requestMatch() {
        listener?.invoke()
    }
}
