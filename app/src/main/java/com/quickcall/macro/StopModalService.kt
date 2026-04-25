package com.quickcall.macro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button

/**
 * 모드 2 시퀀스 동작중에만 화면 상단에 표시되는 카드형 오버레이.
 *
 *  - "매크로 시퀀스 동작중" 텍스트 + 빨간 [중지] 버튼
 *  - 중지 클릭 → CallMacroService.cancelSequence()
 *
 * 시작/종료 명시적 트리거:
 *   StopModalService.show(context)
 *   StopModalService.hide(context)
 *
 * 오버레이 권한이 없으면 표시 자체를 스킵한다.
 */
class StopModalService : Service() {

    companion object {
        private const val ACTION_SHOW = "com.quickcall.macro.STOP_MODAL_SHOW"
        private const val ACTION_HIDE = "com.quickcall.macro.STOP_MODAL_HIDE"

        @Volatile
        var isVisible = false
            private set

        fun show(ctx: Context) {
            val i = Intent(ctx, StopModalService::class.java).setAction(ACTION_SHOW)
            ctx.startService(i)
        }

        fun hide(ctx: Context) {
            val i = Intent(ctx, StopModalService::class.java).setAction(ACTION_HIDE)
            ctx.startService(i)
        }
    }

    private lateinit var wm: WindowManager
    private var view: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showModal()
            ACTION_HIDE -> {
                hideModal()
                stopSelf()
            }
            else -> hideModal()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideModal()
        super.onDestroy()
    }

    private fun showModal() {
        if (view != null) return

        val v = LayoutInflater.from(this).inflate(R.layout.stop_modal, null)
        v.findViewById<Button>(R.id.btnStopSequence).setOnClickListener {
            CallMacroService.instance?.cancelSequence()
            hideModal()
            stopSelf()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        try {
            wm.addView(v, params)
            view = v
            isVisible = true
        } catch (_: Throwable) {
            // 오버레이 권한 없음 등 → 무시
            view = null
            isVisible = false
        }
    }

    private fun hideModal() {
        try {
            view?.let { wm.removeView(it) }
        } catch (_: Throwable) {}
        view = null
        isVisible = false
    }
}
