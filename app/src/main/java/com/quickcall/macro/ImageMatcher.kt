package com.quickcall.macro

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * 가벼운 이미지 매처.
 *
 * "확정추적" 버튼은 다음 특성을 가짐 (사용자 화면 기준):
 *  - 검은(어두운) 배경 위에 빨간 글자
 *  - 화면 하단 중앙 영역에 위치
 *  - 좌우의 "자동중지"(분홍), "확정"(청록) 과 색상이 명확히 구분됨
 *
 * 무거운 OpenCV 템플릿 매칭 대신, 화면 하단 1/5 영역에서
 * "어두운 배경 + 빨간 픽셀이 일정 비율 이상" 인 컬럼을 찾는
 * 컬러 시그니처 방식으로 빠르게 좌표를 산출한다.
 *
 * 약점:
 *  - 같은 줄에 빨간 텍스트 버튼이 여러 개 있으면 오탐 가능
 *  - 그래서 항상 Accessibility 노드 매칭이 우선이고, 폴백으로만 사용
 */
object ImageMatcher {

    data class Match(val x: Int, val y: Int, val score: Float)

    // 빨간 글자 픽셀 판정 (R 우세 + G,B 낮음)
    private fun isRedText(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 150 && g < 90 && b < 90 && r - g > 60 && r - b > 60
    }

    // 어두운 배경 픽셀 판정
    private fun isDarkBg(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r < 70 && g < 70 && b < 70
    }

    /**
     * 비트맵에서 "확정추적" 버튼 추정 좌표 찾기.
     * @return 못 찾으면 null
     */
    fun findTargetButton(bmp: Bitmap): Match? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null

        // 하단 25% 영역만 스캔 (성능)
        val yStart = (h * 0.75f).toInt()
        val yEnd = (h * 0.97f).toInt()
        val rows = yEnd - yStart
        if (rows <= 0) return null

        // 다운샘플 스텝 (속도 vs 정확도 균형)
        val stepX = (w / 200).coerceAtLeast(2)
        val stepY = (rows / 60).coerceAtLeast(2)

        // 가로를 5개 컬럼 영역으로 나누어 각 영역의 빨간/어두운 픽셀 비율 계산
        // 보통 하단 버튼바: [취소][자동중지][확정추적][확정] 순
        val zones = 5
        val zoneW = w / zones
        val redCount = IntArray(zones)
        val darkCount = IntArray(zones)
        val totalCount = IntArray(zones)

        var y = yStart
        while (y < yEnd) {
            var x = 0
            while (x < w) {
                val zoneIdx = (x / zoneW).coerceAtMost(zones - 1)
                val px = bmp.getPixel(x, y)
                totalCount[zoneIdx]++
                if (isRedText(px)) redCount[zoneIdx]++
                else if (isDarkBg(px)) darkCount[zoneIdx]++
                x += stepX
            }
            y += stepY
        }

        // 점수: 빨간 픽셀 비율 + 어두운 배경 비율의 가중합
        var bestZone = -1
        var bestScore = 0f
        for (i in 0 until zones) {
            if (totalCount[i] == 0) continue
            val redRatio = redCount[i].toFloat() / totalCount[i]
            val darkRatio = darkCount[i].toFloat() / totalCount[i]
            // 빨간 텍스트가 최소 0.5% 이상 + 어두운 배경 30%+ 일 때만 후보
            if (redRatio < 0.005f) continue
            if (darkRatio < 0.30f) continue
            val score = redRatio * 100f + darkRatio
            if (score > bestScore) {
                bestScore = score
                bestZone = i
            }
        }

        if (bestZone < 0) return null

        val cx = bestZone * zoneW + zoneW / 2
        val cy = (yStart + yEnd) / 2
        return Match(cx, cy, bestScore)
    }

    /**
     * 좌표가 이전 캐시와 비슷하면 동일하다고 본다.
     */
    fun similar(a: Pair<Int, Int>, b: Pair<Int, Int>, tolerance: Int = 30): Boolean {
        return abs(a.first - b.first) <= tolerance && abs(a.second - b.second) <= tolerance
    }
}
