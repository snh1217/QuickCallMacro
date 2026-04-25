package com.quickcall.macro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * MediaProjection 으로 화면을 주기적으로 캡처하지 않고,
 * Accessibility 가 "노드 매칭 실패" 신호를 보낼 때만 1회 캡처해
 * 이미지 매처를 돌리는 방식.
 *
 * → 평소엔 CPU 사용 0, 노드 매칭이 실패할 때만 비용 발생
 *
 * 주의:
 *  - Android 14+ 부터는 foregroundServiceType="mediaProjection" 필수
 *  - MediaProjection 권한은 사용자가 매번 다이얼로그를 통과시켜야 함
 *    (앱 재실행 시 다시 받아야 함)
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var isRunning = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (data == null || resultCode == 0) {
            Log.e(TAG, "MediaProjection 데이터 없음, 종료")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "MediaProjection 생성 실패")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ 콜백 등록 필수
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                stopSelf()
            }
        }, null)

        setupVirtualDisplay()
        isRunning = true

        // Accessibility 가 폴백을 요청할 때만 매칭 수행
        ImageMatchBridge.setListener {
            captureAndMatchOnce()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        ImageMatchBridge.setListener(null)
        cleanup()
        isRunning = false
        super.onDestroy()
    }

    private fun setupVirtualDisplay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        screenDpi = metrics.densityDpi

        // 매칭 정확도엔 큰 영향 없으니 메모리 절약 위해 다운스케일
        // 단, AccessibilityService 의 dispatchGesture 는 실제 화면 좌표여야 하므로
        // 매칭 결과 좌표를 다시 원래 해상도로 환원해야 함
        val scaleW = screenW
        val scaleH = screenH

        imageReader = ImageReader.newInstance(
            scaleW, scaleH, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "QuickCallCapture",
            scaleW, scaleH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    /**
     * 한 프레임만 가져와서 매칭 시도.
     * 주기적 캡처는 하지 않음 (배터리/성능 절약).
     */
    private fun captureAndMatchOnce() {
        val reader = imageReader ?: return
        val image: Image? = try { reader.acquireLatestImage() } catch (_: Throwable) { null }
        if (image == null) {
            Log.d(TAG, "이미지 비어있음")
            return
        }

        val bmp: Bitmap? = try {
            imageToBitmap(image)
        } catch (t: Throwable) {
            Log.e(TAG, "Bitmap 변환 실패", t)
            null
        } finally {
            image.close()
        }
        if (bmp == null) return

        val match = ImageMatcher.findTargetButton(bmp)
        bmp.recycle()

        if (match != null) {
            Log.d(TAG, "이미지 매칭 성공: (${match.x}, ${match.y}) score=${match.score}")
            // 좌표 캐시 후 즉시 탭
            PreferencesManager.cachedX = match.x
            PreferencesManager.cachedY = match.y
            CallMacroService.instance?.tap(match.x, match.y)
        } else {
            Log.d(TAG, "이미지 매칭 실패")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height).also { bmp.recycle() }
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = getString(R.string.notif_channel_id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
