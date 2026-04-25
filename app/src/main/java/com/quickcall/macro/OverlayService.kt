package com.quickcall.macro

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 화면 위에 작은 원형 토글 버튼을 띄움.
 * - 짧게 탭 → 매크로 ON/OFF
 * - 드래그 → 위치 이동
 *
 * 다른 앱 위에 띄우기 권한(SYSTEM_ALERT_WINDOW)이 필요.
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var wm: WindowManager
    private var view: View? = null
    private lateinit var label: TextView
    private lateinit var params: WindowManager.LayoutParams

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "macro_enabled") applyState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        view = LayoutInflater.from(this).inflate(R.layout.overlay_toggle, null)
        label = view!!.findViewById(R.id.overlayLabel)
        applyState()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 400
        }

        setupTouch()
        wm.addView(view, params)

        // 외부(MainActivity / MacroController) 에서 enabled 가 바뀌면 라벨 자동 갱신
        getSharedPreferences("quickcall_prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        try {
            getSharedPreferences("quickcall_prefs", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (_: Throwable) {}
        try {
            view?.let { wm.removeView(it) }
        } catch (_: Throwable) {}
        view = null
        isRunning = false
        super.onDestroy()
    }

    private fun applyState() {
        view?.post {
            if (PreferencesManager.enabled) {
                label.text = getString(R.string.overlay_label_on)
                label.setBackgroundResource(R.drawable.overlay_bg_on)
            } else {
                label.text = getString(R.string.overlay_label_off)
                label.setBackgroundResource(R.drawable.overlay_bg_off)
            }
        }
    }

    private fun setupTouch() {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 단순 탭 → 매크로 시작/정지 (MacroController 가 권한/캡처 흐름 통제)
                        if (PreferencesManager.enabled) {
                            MacroController.stop(this)
                        } else {
                            MacroController.tryStartFromBackground(this)
                        }
                        applyState()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
