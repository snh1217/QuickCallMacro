package com.quickcall.macro.parser

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 도착지 동/읍/면 추출기. 4단 폴백 (v1.0.4).
 *
 *  1단 (라벨박스): "도착" 라벨이 속한 ViewGroup(부모) 의 모든 자식 +
 *                  부모의 후속 형제 ViewGroup 들 — 단, 형제에 다른
 *                  최상위 라벨(출발/픽업/의뢰)이 있으면 거기서 멈춤
 *  2단 (박스영역): 라벨 박스의 boundsInScreen 기준 — 우측/하단 영역에
 *                  걸치는 모든 노드 텍스트. 다음 라벨 박스 만나면 컷.
 *  3단 (슬라이스): 화면 결합 텍스트의 "도착" 단어 이후 ~200자 슬라이스
 *                  (출발/픽업/의뢰/물품/차량/탁송료/요금/구분/형태/적요/
 *                  상세/인수증 단어 만나면 컷)
 *  4단 (슬래시): 화면 전체 결합 텍스트에서 `/ XX동 / *` 패턴, 마지막 매치
 *                우선 (도착지가 출발지보다 화면 아래에 위치)
 *
 *  라벨 매칭은 정확 일치만: text == "도착" 또는 "도착:".
 *  부분 매칭("도착시간", "도착예정") 절대 사용 금지.
 */
object DestinationParser {

    /** 좌표 폴백의 Y 확장 (px) */
    const val Y_BOX_EXTEND = 50

    /** 슬라이스 폴백의 최대 슬라이스 길이 */
    const val SLICE_MAX = 200

    /** 좌표 기반 라벨 같은 행 폴백의 Y 허용 오차 (px) — 2단 보조 */
    const val Y_TOLERANCE = 100

    /** 3단 슬라이스 컷 단어 */
    private val SLICE_STOP_WORDS = listOf(
        "출발", "픽업", "의뢰", "물품", "차량",
        "탁송료", "요금", "구분", "형태", "적요", "상세", "인수증"
    )

    /** 형제 박스 진입 차단 라벨 (1단) */
    private val SIBLING_STOP_LABELS = listOf("출발", "픽업", "의뢰")

    data class Outcome(
        val tokens: List<String>,
        val tier: String  // "1단:라벨박스" / "2단:박스영역" / "3단:슬라이스" / "4단:슬래시" / "실패"
    ) {
        val token: String? get() = tokens.firstOrNull()
    }

    /** 라벨로 인정할 텍스트 */
    private fun isArrivalLabel(text: String?): Boolean {
        if (text == null) return false
        val t = text.trim()
        return t == "도착" || t == "도착:" || t == "도 착"
    }

    /** 메인 진입점 (런타임용) */
    fun parse(root: AccessibilityNodeInfo): Outcome {
        val pn = ParseNode.fromAccessibility(root)
        return parseNode(pn)
    }

    /** 메인 진입점 (테스트/순수 함수) */
    fun parseNode(root: ParseNode): Outcome {
        val flat = root.flatten()

        // 1단: 라벨 박스 + 후속 형제 박스
        parseByLabelGroup(flat)?.let { return Outcome(it, "1단:라벨박스") }

        // 2단: 라벨 박스 영역 (좌표)
        parseByLabelBoxRegion(flat)?.let { return Outcome(it, "2단:박스영역") }

        // 3단: 텍스트 슬라이스 (200자, stop word 컷)
        parseByTextSlice(flat)?.let { return Outcome(it, "3단:슬라이스") }

        // 4단: 슬래시 패턴 (마지막 매치)
        parseBySlashPattern(flat)?.let { return Outcome(it, "4단:슬래시") }

        return Outcome(emptyList(), "실패")
    }

    // ───────────────────────── 1단 ─────────────────────────

    private fun parseByLabelGroup(flat: List<ParseNode.Flat>): List<String>? {
        val labels = flat.filter { isArrivalLabel(it.node.text) }
        for (lf in labels) {
            val labelBox = lf.parent ?: continue

            // 후보 텍스트 수집 (라벨 박스 자체)
            val candidates = ArrayList<String>(8)
            collectTextsExcludingLabel(labelBox, lf.node, candidates)

            // 라벨 박스의 부모 → 후속 형제 스캔
            val gpFlat = flat.firstOrNull { it.node === labelBox }
            val grandparent = gpFlat?.parent
            if (grandparent != null) {
                val labelBoxIdx = grandparent.indexOfChildIdentity(labelBox)
                if (labelBoxIdx >= 0) {
                    for (i in (labelBoxIdx + 1) until grandparent.children.size) {
                        val sibling = grandparent.children[i]
                        if (containsAnyArrivalSiblingStopLabel(sibling)) break
                        collectTextsExcludingLabel(sibling, null, candidates)
                    }
                }
            }
            val toks = DongTokenExtractor.extractCandidates(candidates)
            if (toks.isNotEmpty()) return toks
        }
        return null
    }

    private fun collectTextsExcludingLabel(node: ParseNode, exclude: ParseNode?, out: MutableList<String>) {
        node.forEachDescendant { n ->
            if (n === exclude) return@forEachDescendant
            val t = n.text
            if (!t.isNullOrBlank()) out.add(t)
        }
    }

    private fun containsAnyArrivalSiblingStopLabel(node: ParseNode): Boolean {
        var found = false
        node.forEachDescendant { n ->
            val t = n.text?.trim() ?: return@forEachDescendant
            if (t in SIBLING_STOP_LABELS || (t.endsWith(":") && t.dropLast(1) in SIBLING_STOP_LABELS)) {
                found = true
            }
        }
        return found
    }

    // ───────────────────────── 2단 ─────────────────────────

    private fun parseByLabelBoxRegion(flat: List<ParseNode.Flat>): List<String>? {
        val labels = flat.filter { isArrivalLabel(it.node.text) }
        if (labels.isEmpty()) return null

        // 다른 stop label 박스들의 top 값 — 컷 기준
        val stopBoxTops = ArrayList<Int>()
        for (f in flat) {
            val t = f.node.text?.trim() ?: continue
            if (t in SIBLING_STOP_LABELS || (t.endsWith(":") && t.dropLast(1) in SIBLING_STOP_LABELS)) {
                val box = f.parent ?: f.node
                stopBoxTops.add(box.bounds.top)
            }
        }

        for (lf in labels) {
            val labelBox = lf.parent ?: lf.node
            val regionTop = labelBox.bounds.top
            val labelBottom = labelBox.bounds.bottom
            val regionLeft = labelBox.bounds.right

            // 라벨 박스 아래 최초의 stop label box top
            val nextStopTop = stopBoxTops
                .filter { it > labelBottom }
                .minOrNull() ?: Int.MAX_VALUE
            val regionBottom = minOf(labelBottom + Y_BOX_EXTEND, nextStopTop)

            // 영역 내 텍스트 수집:
            //  (a) 라벨 박스 우측 (X >= regionLeft) + Y 같은 행 (라벨 박스의 Y 범위와 겹침)
            //  (b) 라벨 박스 아래 영역 (Y > labelBottom, 단 < regionBottom)
            val candidates = ArrayList<String>(16)
            for (f in flat) {
                if (f.node === lf.node) continue
                val text = f.node.text
                if (text.isNullOrBlank()) continue
                if (isArrivalLabel(text)) continue
                val b = f.node.bounds

                // 같은 행: 라벨 박스 Y 범위와 겹침 + 우측
                val sameRow = b.left >= regionLeft &&
                        b.bottom >= regionTop && b.top <= labelBottom
                // 아래 영역
                val belowBox = b.top in (labelBottom + 1)..regionBottom
                // 또는 라벨 박스 같은 Y 범위에 들어가는 임의 노드 (Y±tol 보조)
                val nearY = abs(b.centerY - lf.node.bounds.centerY) <= Y_TOLERANCE &&
                        b.left >= lf.node.bounds.left

                if (sameRow || belowBox || nearY) {
                    candidates.add(text)
                }
            }
            val toks = DongTokenExtractor.extractCandidates(candidates)
            if (toks.isNotEmpty()) return toks
        }
        return null
    }

    // ───────────────────────── 3단 ─────────────────────────

    private fun parseByTextSlice(flat: List<ParseNode.Flat>): List<String>? {
        val joined = flat
            .mapNotNull { it.node.text }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val arrIdx = joined.indexOf("도착")
        if (arrIdx < 0) return null

        var sliceEnd = (arrIdx + SLICE_MAX).coerceAtMost(joined.length)
        for (sw in SLICE_STOP_WORDS) {
            val idx = joined.indexOf(sw, arrIdx + 1)
            if (idx in (arrIdx + 1) until sliceEnd) sliceEnd = idx
        }
        val slice = joined.substring(arrIdx, sliceEnd)
        val toks = DongTokenExtractor.extractCandidates(listOf(slice))
        return if (toks.isEmpty()) null else toks
    }

    // ───────────────────────── 4단 ─────────────────────────

    /**
     * 화면 전체 텍스트에서 슬래시 패턴 검색.
     * "/ XX동 / *" 우선 (가장 신뢰), 없으면 "/ XX동 / YY".
     * 같은 패턴이 여러 개면 마지막 매치 채택 — 도착지가 출발지보다 화면 아래.
     */
    private fun parseBySlashPattern(flat: List<ParseNode.Flat>): List<String>? {
        val joined = flat
            .mapNotNull { it.node.text }
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val withStarSuffix = Regex("""/\s*([가-힣]+(?:동|읍|면))\s*/\s*\*""")
            .findAll(joined)
            .map { it.groupValues[1] }
            .filter { !DongTokenExtractor.isMetaToken(it) }
            .toList()
        if (withStarSuffix.isNotEmpty()) return listOf(withStarSuffix.last())

        // 통칭 (suffix 없는 한글 2~5자): "/ 동탄 / *"
        val withStarBare = Regex("""/\s*([가-힣]{2,5})\s*/\s*\*""")
            .findAll(joined)
            .map { it.groupValues[1] }
            .filter { !DongTokenExtractor.isMetaToken(it) }
            .toList()
        if (withStarBare.isNotEmpty()) return listOf(withStarBare.last())

        val generalSuffix = Regex("""/\s*([가-힣]+(?:동|읍|면))\s*/\s*[^/\s]+""")
            .findAll(joined)
            .map { it.groupValues[1] }
            .filter { !DongTokenExtractor.isMetaToken(it) }
            .toList()
        if (generalSuffix.isNotEmpty()) return listOf(generalSuffix.last())
        return null
    }
}
