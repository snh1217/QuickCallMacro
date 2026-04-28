package com.quickcall.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.quickcall.macro.data.DistrictRepository
import com.quickcall.macro.data.DistrictSlot
import com.quickcall.macro.parser.DestinationParser
import com.quickcall.macro.parser.DistrictFilter
import com.quickcall.macro.parser.DistrictKeyGenerator
import com.quickcall.macro.parser.ParseNode

/**
 * 핵심 매크로 서비스.
 *
 * 모드 1 (즉시 추적): 콜 상세 화면 → "확정추적" 즉시 탭
 * 모드 2 (홀드 후 추적):
 *   - 콜 상세 화면 → "확정" 즉시 탭
 *   - 1초 간격으로 "확정" 반복 (총 5초간)
 *   - 5초 경과 후 "확정추적" 1회 탭, 시퀀스 종료
 *   - 도중에 콜 상세 화면이 사라지면 즉시 중단
 *   - 사용자가 모달의 "중지" 누르면 즉시 중단
 *
 *  속도 최적화 포인트:
 *  - 백그라운드 스레드로 위임하지 않음 (콜백 → 즉시 탭)
 *  - 모드 1 에서는 좌표 캐싱 사용 (PreferencesManager.cachedX/Y)
 *  - 모드 2 는 UI 가 중간에 변할 수 있어서 캐싱 안 함
 *  - 디스패치 제스처 duration = 1ms (사실상 즉시)
 */
class CallMacroService : AccessibilityService() {

    companion object {
        private const val TAG = "CallMacroService"

        @Volatile
        var instance: CallMacroService? = null
            private set

        /** 외부(OverlayService 등)에서 즉시 탭 트리거 시 사용 */
        fun triggerScan() {
            instance?.scanAndTap()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 같은 콜에 대해 중복 탭 방지용 디바운스 (모드 1 전용, ms) */
    private var lastTapAt = 0L
    private val tapCooldownMs = 1500L

    /** 모드 2 시퀀스 진행중 여부 */
    @Volatile
    private var sequenceRunning = false

    /** 모드 2 시퀀스 시작 시각 */
    private var sequenceStartedAt = 0L

    /** 진행 중인 시퀀스 Runnable 들 (취소용) */
    private val sequenceRunnables = mutableListOf<Runnable>()

    /** 슬롯 캐싱: id + JSON 모두 같을 때만 재사용 (슬롯 내용 편집 즉시 반영) */
    private var cachedSlotId = -1
    private var cachedSlotJson: String? = null
    private var cachedSlotKeys: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        cancelSequence()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClickEvent(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!PreferencesManager.enabled) return
                // 시퀀스 진행중이면 화면 이탈 감지
                if (sequenceRunning) {
                    val root = rootInActiveWindow
                    if (root == null || !isCallDetailScreenVisible(root)) {
                        Log.d(TAG, "콜 상세 화면 이탈 감지 → 시퀀스 중단")
                        debug("화면 이탈 → 시퀀스 중단")
                        cancelSequence()
                    }
                    return
                }
                scanAndTap()
            }
        }
    }

    /**
     * 사용자가 화면에서 직접 누른 클릭 이벤트.
     * "자동중지" 버튼을 눌렀다면 매크로 자체를 OFF.
     */
    private fun handleClickEvent(event: AccessibilityEvent) {
        if (!PreferencesManager.enabled) return
        val pieces = StringBuilder()
        event.text.forEach { pieces.append(it).append(' ') }
        event.contentDescription?.let { pieces.append(it).append(' ') }
        event.source?.text?.let { pieces.append(it).append(' ') }
        event.source?.contentDescription?.let { pieces.append(it).append(' ') }
        val combined = pieces.toString()
        if (combined.contains("자동중지") || combined.contains("자동 중지")) {
            Log.i(TAG, "자동중지 감지 → 매크로 OFF")
            debug("자동중지 감지 → 매크로 OFF")
            try { MacroController.stop(this) } catch (_: Throwable) {}
        }
    }

    /**
     * 현재 화면에서 타겟을 찾고, 조건을 통과하면 모드별 동작 수행.
     */
    fun scanAndTap() {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return

        // 0) 구 필터 게이트 (활성 슬롯이 있을 때만)
        if (!passDistrictFilter(root)) return

        // 필터 검사 (거리/요금)
        if (PreferencesManager.filterEnabled) {
            val (distance, fare) = parseDistanceAndFare(root)
            if (!passFilter(distance, fare)) {
                Log.d(TAG, "필터 미통과: distance=$distance, fare=$fare")
                return
            }
        }

        when (PreferencesManager.macroMode) {
            MacroMode.MODE_1_INSTANT_TRACK -> mode1InstantTrack(root)
            MacroMode.MODE_2_HOLD_THEN_TRACK -> mode2StartSequence(root)
        }
    }

    // ───────────────────────────── 모드 1 ─────────────────────────────

    private fun mode1InstantTrack(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastTapAt < tapCooldownMs) return

        // 캐시된 좌표가 있으면 즉시 탭 (가장 빠른 경로)
        val cx = PreferencesManager.cachedX
        val cy = PreferencesManager.cachedY
        if (cx > 0 && cy > 0 && hasTargetVisible(root)) {
            lastTapAt = now
            tap(cx, cy)
            Log.d(TAG, "캐시 좌표로 즉시 탭: ($cx, $cy)")
            debug("캐시 좌표 사용 ($cx, $cy)")
            return
        }

        val target = findNodeByText(root, PreferencesManager.targetText, secondary = listOf("확정추적", "추적", "확정"))
        if (target != null) {
            val rect = Rect()
            target.getBoundsInScreen(rect)
            val x = rect.centerX()
            val y = rect.centerY()
            if (x > 0 && y > 0) {
                lastTapAt = now
                PreferencesManager.cachedX = x
                PreferencesManager.cachedY = y
                tap(x, y)
                Log.d(TAG, "노드 매칭 탭: ($x, $y)")
                debug("노드 매칭됨 ($x, $y)")
                return
            }
        }

        // 노드 폴백 실패 시 → 이미지 매처에 알림
        debug("이미지 폴백 진입")
        ImageMatchBridge.requestMatch()
    }

    // ───────────────────────────── 모드 2 ─────────────────────────────

    private fun mode2StartSequence(root: AccessibilityNodeInfo) {
        if (sequenceRunning) return
        // 콜 상세 화면이 아니면 시작하지 않음
        if (!isCallDetailScreenVisible(root)) return

        sequenceRunning = true
        sequenceStartedAt = System.currentTimeMillis()
        // Pref 에서 사용자 설정 시간 읽기 (기본 5000 / 1000)
        val holdMs = PreferencesManager.mode2HoldDurationMs.toLong()
        val tickMs = PreferencesManager.mode2TapIntervalMs.toLong()
        Log.i(TAG, "모드 2 시퀀스 시작 (hold=${holdMs}ms, tick=${tickMs}ms)")
        debug("모드2 시퀀스 시작 (홀드 ${holdMs}ms, 간격 ${tickMs}ms)")
        StopModalService.show(this)

        // 즉시 첫 "확정" 탭
        tapConfirmNow()

        // tickMs 간격으로 "확정" 반복 (holdMs 까지)
        var t = tickMs
        while (t < holdMs) {
            val r = Runnable {
                if (!sequenceRunning) return@Runnable
                tapConfirmNow()
            }
            sequenceRunnables.add(r)
            mainHandler.postDelayed(r, t)
            t += tickMs
        }

        // holdMs 후 "확정추적" 1회 탭
        val finalR = Runnable {
            if (!sequenceRunning) return@Runnable
            tapTrackOnceAndFinish()
        }
        sequenceRunnables.add(finalR)
        mainHandler.postDelayed(finalR, holdMs)
    }

    private fun tapConfirmNow() {
        val root = rootInActiveWindow ?: return
        // 매 tap 직전 화면 + 필터 재검증 — 시퀀스 도중 다른 콜로 바뀌어도 안전
        if (!isCallDetailScreenVisible(root)) {
            debug("모드2 화면 이탈 → 시퀀스 중단")
            cancelSequence()
            return
        }
        if (!passDistrictFilter(root)) {
            debug("모드2 필터 미통과 → 시퀀스 중단")
            cancelSequence()
            return
        }
        val node = findConfirmNode(root) ?: run {
            Log.d(TAG, "확정 노드 없음")
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX()
        val y = rect.centerY()
        if (x > 0 && y > 0) {
            tap(x, y)
            Log.d(TAG, "[모드2] 확정 탭: ($x, $y)")
            debug("모드2 확정 탭")
        }
    }

    private fun tapTrackOnceAndFinish() {
        val root = rootInActiveWindow
        if (root != null) {
            // 마지막 추적 탭도 필터 재검증
            if (!passDistrictFilter(root)) {
                debug("모드2 추적 직전 필터 미통과 → 시퀀스 중단")
                finishSequence()
                return
            }
            val node = findNodeByText(root, "확정추적", secondary = listOf("추적"))
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val x = rect.centerX()
                val y = rect.centerY()
                if (x > 0 && y > 0) {
                    tap(x, y)
                    Log.i(TAG, "[모드2] 마지막 확정추적 탭: ($x, $y)")
                    debug("모드2 종료 (확정추적)")
                }
            } else {
                Log.d(TAG, "[모드2] 확정추적 노드 없음")
                debug("모드2 종료 (추적 노드 없음)")
            }
        }
        finishSequence()
    }

    /** 외부(StopModal)에서 호출 가능 */
    fun cancelSequence() {
        if (!sequenceRunning && sequenceRunnables.isEmpty()) return
        Log.i(TAG, "시퀀스 취소")
        finishSequence()
    }

    private fun finishSequence() {
        sequenceRunning = false
        for (r in sequenceRunnables) mainHandler.removeCallbacks(r)
        sequenceRunnables.clear()
        StopModalService.hide(this)
    }

    /**
     * "확정" 노드 찾기. "확정추적" 과는 구분됨.
     * "확정" 자체이거나 "확정(숫자)" 패턴(예: 확정(8))인 텍스트만 인정.
     */
    private fun findConfirmNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText("확정") ?: return null
        if (nodes.isEmpty()) return null

        val confirmRegex = Regex("""^확정(\s*\(\s*\d+\s*\))?\s*$""")
        // 클릭 가능 + 정확히 "확정" 또는 "확정(숫자)" 패턴
        val candidates = nodes.filter { n ->
            val txt = (n.text?.toString() ?: "").trim()
            txt.isNotEmpty() && confirmRegex.matches(txt)
        }
        return candidates.firstOrNull { it.isClickable }
            ?: candidates.firstOrNull()
    }

    /** 콜 상세 화면 판정: "확정" 또는 "확정추적" 노드가 보이면 상세 화면으로 간주 */
    private fun isCallDetailScreenVisible(root: AccessibilityNodeInfo): Boolean {
        val track = root.findAccessibilityNodeInfosByText("확정추적")
        if (!track.isNullOrEmpty()) return true
        return findConfirmNode(root) != null
    }

    // ───────────────────────────── 공용 ─────────────────────────────

    private fun hasTargetVisible(root: AccessibilityNodeInfo): Boolean {
        val text = PreferencesManager.targetText
        if (text.isBlank()) return false
        return root.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        primary: String,
        secondary: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        if (primary.isNotBlank()) {
            val exact = root.findAccessibilityNodeInfosByText(primary)
            if (!exact.isNullOrEmpty()) {
                return exact.firstOrNull { it.isClickable } ?: exact.first()
            }
        }
        for (kw in secondary) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                val clickable = nodes.firstOrNull { it.isClickable }
                if (clickable != null) return clickable
            }
        }
        return null
    }

    /**
     * 화면 내 모든 텍스트 노드를 훑어서 거리(km) / 요금(원) 추출.
     */
    private fun parseDistanceAndFare(root: AccessibilityNodeInfo): Pair<Float?, Int?> {
        var distance: Float? = null
        var fare: Int? = null

        val distRegex = Regex("""(\d{1,3}(?:\.\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)
        val fareRegex = Regex("""(\d{4,7})""")

        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = (n.text?.toString() ?: "") + " " +
                    (n.contentDescription?.toString() ?: "")
            if (t.isNotBlank()) {
                if (distance == null) {
                    distRegex.find(t)?.let { distance = it.groupValues[1].toFloatOrNull() }
                }
                if (fare == null && (t.contains("요금") || t.contains("원") || t.contains("신용"))) {
                    fareRegex.find(t)?.let { fare = it.groupValues[1].toIntOrNull() }
                }
            }
            for (i in 0 until n.childCount) {
                visit(n.getChild(i))
            }
        }

        visit(root)
        return distance to fare
    }

    private fun passFilter(distance: Float?, fare: Int?): Boolean {
        val minD = PreferencesManager.minDistance
        val maxD = PreferencesManager.maxDistance
        val minF = PreferencesManager.minFare

        if (minD > 0f && (distance == null || distance < minD)) return false
        if (maxD > 0f && (distance == null || distance > maxD)) return false
        if (minF > 0 && (fare == null || fare < minF)) return false
        return true
    }

    // ─── 구 필터 ─────────────────────────────────────

    /**
     * 활성 슬롯이 있으면 도착지 동/읍/면을 파싱해서 매칭.
     * 도착지 파싱 실패 시 안전하게 차단.
     * 활성 슬롯 없거나 슬롯이 비어있으면 항상 통과.
     */
    /**
     * 도착지 필터 통과 여부.
     *  - activeSlotId == 0 → 항상 통과 (사용 안 함)
     *  - activeSlotId != 0 + 슬롯 비어있음 → 차단 (안전 정책)
     *  - 도착지 추출 실패 → 차단
     *  - 후보 키 ∩ 슬롯 키 비어있음 → 차단
     *
     *  슬롯 캐시는 active id + JSON 문자열 둘 다 일치할 때만 재사용 → 슬롯 편집 즉시 반영.
     */
    private fun passDistrictFilter(root: AccessibilityNodeInfo): Boolean {
        val active = PreferencesManager.activeSlotId
        if (active == 0) {
            debug("활성 슬롯 없음 → 통과")
            return true
        }

        val json = PreferencesManager.getSlotSelectionJson(active)
        if (cachedSlotId != active || cachedSlotJson != json) {
            try { DistrictRepository.ensureLoaded(applicationContext) } catch (_: Throwable) {}
            val slot = DistrictSlot.fromJson(
                active, PreferencesManager.getSlotName(active), json
            )
            cachedSlotKeys = DistrictRepository.normalizedKeysForSlot(slot)
            cachedSlotId = active
            cachedSlotJson = json
        }

        val pn = try { ParseNode.fromAccessibility(root) }
        catch (t: Throwable) { Log.w(TAG, "ParseNode 변환 실패", t); null }
            ?: run { debug("도착지 노드 변환 실패 → 차단"); return false }

        val result = DistrictFilter.check(pn, cachedSlotKeys)
        return when (result) {
            is DistrictFilter.Result.Pass -> {
                debug("필터 통과 [${result.tier}]: ${result.tokens.first()} → ${result.matchedKeys}")
                true
            }
            is DistrictFilter.Result.BlockEmptySlot -> {
                debug("슬롯 비어있음 → 차단 (슬롯 편집 필요)")
                false
            }
            is DistrictFilter.Result.BlockParseFail -> {
                debug("도착지 노드 미발견 → 차단")
                false
            }
            is DistrictFilter.Result.BlockNoMatch -> {
                debug("필터 차단 [${result.tier}]: ${result.tokens.first()} → ${result.destKeys}")
                false
            }
        }
    }

    private fun debug(msg: String) {
        if (!PreferencesManager.debugToast) return
        mainHandler.post {
            try { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    /**
     * 좌표 (x,y) 에 즉시 탭 주입.
     * duration = 1ms — 사실상 클릭과 같음.
     */
    fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, mainHandler)
    }
}
