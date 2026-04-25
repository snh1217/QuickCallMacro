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
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.quickcall.macro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        /** OverlayService 등에서 "시작" 트리거를 위해 액티비티를 띄울 때 사용 */
        const val EXTRA_REQUEST_START = "request_start"

        /** 외부(MacroController) 에서 액티비티 컨텍스트 없이 권한 체크할 수 있도록 */
        fun isAccessibilityEnabledStatic(ctx: Context): Boolean {
            val expected = ComponentName(ctx, CallMacroService::class.java).flattenToString()
            val enabled = try {
                Settings.Secure.getString(
                    ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
            } catch (_: Throwable) { "" }
            if (enabled.isNotEmpty()) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabled)
                for (id in splitter) {
                    if (id.equals(expected, ignoreCase = true)) return true
                    if (id.endsWith("/.${CallMacroService::class.java.simpleName}")) return true
                }
            }
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            return list.any { info ->
                info.id?.let { id ->
                    id.equals(expected, ignoreCase = true) ||
                            (id.contains(CallMacroService::class.java.simpleName, ignoreCase = true) &&
                                    id.contains(ctx.packageName, ignoreCase = true))
                } == true
            }
        }
    }

    private lateinit var b: ActivityMainBinding

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleProjectionResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        loadValues()
        setupListeners()

        // 외부에서 시작 트리거가 들어왔을 때 (오버레이 토글 ON 등)
        handleStartIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartIntent(intent)
    }

    private fun handleStartIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_START, false) == true) {
            // 의도적 트리거 — 다음 onResume 한 번만 처리하고 클리어
            intent.removeExtra(EXTRA_REQUEST_START)
            // UI 가 준비된 다음 호출되도록 post
            b.root.post { startMacroFromUi() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 자동 캡처 요청 제거 — 명시적 시작 액션이 있어야만 호출됨
    }

    private fun setupListeners() {
        b.btnGrantAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {}
        }
        b.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Throwable) {}
            } else {
                Toast.makeText(this, "이미 권한이 있습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnToggleOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                b.btnToggleOverlay.setText(R.string.btn_start_overlay)
            } else {
                startService(Intent(this, OverlayService::class.java))
                b.btnToggleOverlay.setText(R.string.btn_stop_overlay)
            }
        }
        b.btnStartStop.setOnClickListener {
            if (PreferencesManager.enabled) {
                MacroController.stop(this)
            } else {
                startMacroFromUi()
            }
            refreshStatus()
        }
        b.btnDistrictManage.setOnClickListener {
            startActivity(Intent(this, com.quickcall.macro.ui.DistrictSettingsActivity::class.java))
        }
        b.btnSave.setOnClickListener { saveValues() }
    }

    private fun startMacroFromUi() {
        MacroController.tryStartFromActivity(this)
        refreshStatus()
    }

    /** MacroController 가 호출 — 캡처 다이얼로그 1회 표시 */
    fun requestProjectionDialog() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (t: Throwable) {
            Toast.makeText(this, "화면 캡처 다이얼로그 호출 실패: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleProjectionResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK || result.data == null) {
            // 사용자가 캡처 권한 거절 — 이미지 폴백 없이 진행할지 확인
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_capture_denied_title)
                .setMessage(R.string.dlg_capture_denied_msg)
                .setPositiveButton(R.string.dlg_continue_without) { _, _ ->
                    MacroController.finalizeStart(this)
                    refreshStatus()
                }
                .setNegativeButton(R.string.dlg_cancel, null)
                .show()
            return
        }
        // 캡처 권한 승인 → 캡처 서비스 시작
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        MacroController.finalizeStart(this)
        refreshStatus()
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
        b.swDebugToast.isChecked = PreferencesManager.debugToast
    }

    private fun saveValues() {
        val target = b.etTargetText.text?.toString()?.trim().orEmpty()
        if (target.isBlank()) {
            Toast.makeText(this, "탐지 텍스트를 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (target != PreferencesManager.targetText) {
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
            PreferencesManager.clearCachedCoords()
        }
        PreferencesManager.macroMode = newMode
        PreferencesManager.debugToast = b.swDebugToast.isChecked
        Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val accOn = isAccessibilityEnabledStatic(this)
        val ovOn = Settings.canDrawOverlays(this)
        b.tvAccessibilityStatus.setText(
            if (accOn) R.string.status_accessibility_on else R.string.status_accessibility_off
        )
        b.tvOverlayStatus.setText(
            if (ovOn) R.string.status_overlay_on else R.string.status_overlay_off
        )
        b.btnToggleOverlay.setText(
            if (OverlayService.isRunning) R.string.btn_stop_overlay
            else R.string.btn_start_overlay
        )
        // 매크로 ON/OFF 큰 상태표시
        val running = PreferencesManager.enabled
        b.tvMacroState.setText(if (running) R.string.macro_state_on else R.string.macro_state_off)
        b.tvMacroState.setTextColor(
            getColor(if (running) R.color.overlay_on else R.color.text_muted)
        )
        b.btnStartStop.setText(if (running) R.string.btn_stop_macro else R.string.btn_start_macro)
        b.btnStartStop.setBackgroundColor(
            getColor(if (running) R.color.overlay_off else R.color.overlay_on)
        )

        // 권한 미비 시 안내 문구 표시
        b.tvOnboardingHint.visibility =
            if (accOn && ovOn) android.view.View.GONE
            else android.view.View.VISIBLE

        // 활성 슬롯 표시
        val active = PreferencesManager.activeSlotId
        val activeLabel = if (active == 0) {
            getString(R.string.district_active_none)
        } else {
            val name = PreferencesManager.getSlotName(active)
            val cnt = PreferencesManager.getSlotKeys(active).size
            "슬롯 $active: $name (선택 $cnt)"
        }
        b.tvDistrictActive.text = getString(R.string.fmt_district_active_label, activeLabel)
    }
}
