package com.quickcall.macro.parser

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 도착지 동/읍/면 추출기. 3단 폴백.
 *
 *  1단: "도착" 라벨 노드의 같은 부모 형제 텍스트에서 추출
 *  2단: "도착" 라벨 좌표 기준 같은 행(Y±tol) + 오른쪽 노드들에서 추출
 *  3단: 화면 전체 결합 텍스트에서 "도착" 단어 이후 ~50자 슬라이스
 *       (단, 그 안에서 "출발" 단어 만나면 거기서 컷)
 *
 *  중요: "도착" 부분 매칭 절대 사용 금지.
 *       정확히 "도착" 또는 "도착:" 인 노드만 라벨로 인정.
 */
object DestinationParser {

    /** 좌표 기반 폴백의 Y 허용 오차 (px) */
    const val Y_TOLERANCE = 100

    /** 텍스트 슬라이스 폴백의 최대 슬라이스 길이 */
    const val SLICE_MAX = 50

    data class Outcome(
        val token: String?,  // null = 추출 실패
        val tier: String     // "1단" / "2단" / "3단" / "실패"
    )

    /** 라벨로 인정할 텍스트 */
    private fun isArrivalLabel(text: String?): Boolean {
        if (text == null) return false
        val t = text.trim()
        return t == "도착" || t == "도착:" || t == "도 착"
    }

    private fun isDepartureLabel(text: String?): Boolean {
        if (text == null) return false
        val t = text.trim()
        return t == "출발" || t == "출발:" || t == "출 발"
    }

    /** 메인 진입점 (런타임용) */
    fun parse(root: AccessibilityNodeInfo): Outcome {
        val pn = ParseNode.fromAccessibility(root)
        return parseNode(pn)
    }

    /** 메인 진입점 (테스트/순수 함수) */
    fun parseNode(root: ParseNode): Outcome {
        val flat = root.flatten()
        val labels = flat.filter { isArrivalLabel(it.node.text) }

        // 1단: 형제 노드
        for (lf in labels) {
            val parent = lf.parent ?: continue
            val sibTexts = parent.children
                .filter { it !== lf.node }
                .mapNotNull { it.text }
                .filter { it.isNotBlank() && !isArrivalLabel(it) && !isDepartureLabel(it) }
            val tok = DongTokenExtractor.extract(sibTexts)
            if (tok != null) return Outcome(tok, "1단")
        }

        // 2단: 같은 행 + 오른쪽
        for (lf in labels) {
            val labelBounds = lf.node.bounds
            val sameRow = flat
                .asSequence()
                .filter { it.node !== lf.node }
                .filter { !it.node.text.isNullOrBlank() }
                .filter { !isArrivalLabel(it.node.text) && !isDepartureLabel(it.node.text) }
                .filter { abs(it.node.bounds.centerY - labelBounds.centerY) <= Y_TOLERANCE }
                .filter { it.node.bounds.left >= labelBounds.left }  // 라벨 왼쪽 끝 이상이면 같은 줄로 인정
                .mapNotNull { it.node.text }
                .toList()
            val tok = DongTokenExtractor.extract(sameRow)
            if (tok != null) return Outcome(tok, "2단")
        }

        // 3단: 텍스트 슬라이스
        val joined = flat
            .mapNotNull { it.node.text }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val arrIdx = joined.indexOf("도착")
        if (arrIdx >= 0) {
            val end = (arrIdx + SLICE_MAX).coerceAtMost(joined.length)
            var sliceEnd = end
            // 슬라이스 안에서 "출발" 만나면 컷
            val depIdx = joined.indexOf("출발", arrIdx + 1)
            if (depIdx in (arrIdx + 1) until sliceEnd) sliceEnd = depIdx
            val slice = joined.substring(arrIdx, sliceEnd)
            val tok = DongTokenExtractor.extract(listOf(slice))
            if (tok != null) return Outcome(tok, "3단")
        }

        return Outcome(null, "실패")
    }
}
