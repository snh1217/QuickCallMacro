package com.quickcall.macro

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.quickcall.macro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "이미지 폴백 활성화", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        loadValues()

        b.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, "이미 권한이 있습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnToggleOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val running = isOverlayRunning()
            if (running) {
                stopService(Intent(this, OverlayService::class.java))
                b.btnToggleOverlay.setText(R.string.btn_start_overlay)
            } else {
                startService(Intent(this, OverlayService::class.java))
                b.btnToggleOverlay.setText(R.string.btn_stop_overlay)
            }
        }
        b.btnSave.setOnClickListener { saveValues() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 접근성이 켜졌고 화면 캡처가 아직 안 됐다면 한 번 요청
        if (isAccessibilityEnabled() && !ScreenCaptureService.isRunning) {
            requestProjection()
        }
    }

    private fun loadValues() {
        b.etTargetText.setText(PreferencesManager.targetText)
        b.swFilter.isChecked = PreferencesManager.filterEnabled
        b.etMinDistance.setText(
            if (PreferencesManager.minDistance > 0f) PreferencesManager.minDistance.toString() else ""
        )
        b.etMaxDistance.setText(
            if (PreferencesManager.maxDistance > 0f) PreferencesManager.maxDistance.toString() else ""
        )
        b.etMinFare.setText(
            if (PreferencesManager.minFare > 0) PreferencesManager.minFare.toString() else ""
        )
        when (PreferencesManager.macroMode) {
            MacroMode.MODE_1_INSTANT_TRACK -> b.rbMode1.isChecked = true
            MacroMode.MODE_2_HOLD_THEN_TRACK -> b.rbMode2.isChecked = true
        }
    }

    private fun saveValues() {
        val target = b.etTargetText.text?.toString()?.trim().orEmpty()
        if (target.isBlank()) {
            Toast.makeText(this, "탐지 텍스트를 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (target != PreferencesManager.targetText) {
            // 타겟이 바뀌었으면 캐시 좌표 무효화
            PreferencesManager.clearCachedCoords()
        }
        PreferencesManager.targetText = target
        PreferencesManager.filterEnabled = b.swFilter.isChecked
        PreferencesManager.minDistance = b.etMinDistance.text?.toString()?.toFloatOrNull() ?: 0f
        PreferencesManager.maxDistance = b.etMaxDistance.text?.toString()?.toFloatOrNull() ?: 0f
        PreferencesManager.minFare = b.etMinFare.text?.toString()?.toIntOrNull() ?: 0
        val newMode = if (b.rbMode2.isChecked) MacroMode.MODE_2_HOLD_THEN_TRACK
        else MacroMode.MODE_1_INSTANT_TRACK
        if (newMode != PreferencesManager.macroMode) {
            // 모드가 바뀌면 좌표 캐시 무효화 (모드 1↔2 좌표 의미 다름)
            PreferencesManager.clearCachedCoords()
        }
        PreferencesManager.macroMode = newMode
        Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        b.tvAccessibilityStatus.setText(
            if (isAccessibilityEnabled()) R.string.status_accessibility_on
            else R.string.status_accessibility_off
        )
        b.tvOverlayStatus.setText(
            if (Settings.canDrawOverlays(this)) R.string.status_overlay_on
            else R.string.status_overlay_off
        )
        b.btnToggleOverlay.setText(
            if (isOverlayRunning()) R.string.btn_stop_overlay
            else R.string.btn_start_overlay
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, CallMacroService::class.java).flattenToString()
        // 1) Settings.Secure 직접 조회 (가장 확실)
        val enabled = try {
            Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
        } catch (_: Throwable) { "" }
        if (enabled.isNotEmpty()) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (id in splitter) {
                if (id.equals(expected, ignoreCase = true)) return true
                // 일부 OEM 은 . 축약 형태로 저장
                if (id.endsWith("/.${CallMacroService::class.java.simpleName}")) return true
            }
        }
        // 2) 폴백: AccessibilityManager
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return list.any { info ->
            info.id?.let { id ->
                id.equals(expected, ignoreCase = true) ||
                        id.contains(CallMacroService::class.java.simpleName, ignoreCase = true) &&
                        id.contains(packageName, ignoreCase = true)
            } == true
        }
    }

    private fun isOverlayRunning(): Boolean = OverlayService.isRunning

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
