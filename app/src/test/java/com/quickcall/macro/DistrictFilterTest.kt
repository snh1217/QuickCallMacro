package com.quickcall.macro

import com.quickcall.macro.parser.BBox
import com.quickcall.macro.parser.DistrictFilter
import com.quickcall.macro.parser.DistrictKeyGenerator
import com.quickcall.macro.parser.ParseNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.7 — 모드 2 필터 우회 버그 회귀 테스트.
 *
 * 핵심 보증:
 *  - 슬롯 키 셋이 비어있으면 (활성 슬롯 != 0 인데 비어있음) 무조건 차단
 *  - 도착지 추출 실패 시 차단
 *  - 도착지 후보의 키 union 과 슬롯 키 교집합이 없으면 차단
 *  - 매칭되면 통과
 *
 * 모드 분기와 무관하게 DistrictFilter 만으로 결정되므로 모드 1/2 모두 동일하게 적용됨을 보장.
 */
class DistrictFilterTest {

    private fun n(text: String?, bx: BBox = BBox(0, 0, 0, 0), children: List<ParseNode> = emptyList()) =
        ParseNode(text, bx, children)

    private fun screenWith(destText: String): ParseNode {
        // 표준 화면 모사: 출발 행 + 도착 라벨 행 + 도착 값 행
        val depRow = n(null, BBox(0, 100, 1080, 180), listOf(
            n("출발", BBox(20, 120, 80, 160)),
            n("/ 강북 미아동 / *", BBox(120, 120, 600, 160))
        ))
        val arrLabel = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260))
        ))
        val arrValue = n(null, BBox(0, 290, 1080, 370), listOf(
            n("서명", BBox(20, 310, 80, 350)),
            n(destText, BBox(120, 310, 800, 350))
        ))
        return n(null, BBox(0, 0, 1080, 1920), listOf(depRow, arrLabel, arrValue))
    }

    private fun emptyScreen(): ParseNode = n(null, BBox(0, 0, 1080, 1920), listOf(
        n("아무 라벨 없음", BBox(20, 100, 500, 140))
    ))

    private fun slotKeys(vararg entries: Triple<String, String?, String>): Set<String> {
        val out = HashSet<String>()
        for ((sg, gu, dong) in entries) {
            out.addAll(DistrictKeyGenerator.keysForSlotEntry(sg, gu, dong))
        }
        return out
    }

    // ───────────────────────── 회귀 테스트 ─────────────────────────

    /** 핵심: 슬롯 범위 밖 도착지 → 차단 (모드와 무관) */
    @Test fun 화성시_슬롯_강남도착_차단() {
        val slot = slotKeys(
            Triple("화성", null, "동탄1동"),
            Triple("화성", null, "봉담읍")
        )
        val screen = screenWith("/ 강남역삼동 / *")
        val r = DistrictFilter.check(screen, slot)
        assertFalse("결과: $r", r.passed)
        assertTrue(r is DistrictFilter.Result.BlockNoMatch)
    }

    /** 핵심: 슬롯 범위 안 도착지 → 통과 */
    @Test fun 화성시_슬롯_동탄도착_통과() {
        val slot = slotKeys(
            Triple("화성", null, "동탄1동"),
            Triple("화성", null, "동탄2동"),
            Triple("화성", null, "동탄면")
        )
        val screen = screenWith("/ 동탄 / *")
        val r = DistrictFilter.check(screen, slot)
        assertTrue("결과: $r", r.passed)
    }

    /** 슬롯 키 셋이 비어있으면 (활성인데 비어있음) → 차단 (안전 정책) */
    @Test fun 슬롯_비어있음_차단() {
        val r = DistrictFilter.check(screenWith("/ 동탄 / *"), emptySet())
        assertFalse(r.passed)
        assertTrue(r is DistrictFilter.Result.BlockEmptySlot)
    }

    /** 도착지 추출 완전 실패 → 차단 */
    @Test fun 도착지_추출_실패_차단() {
        val slot = slotKeys(Triple("화성", null, "동탄면"))
        val r = DistrictFilter.check(emptyScreen(), slot)
        assertFalse(r.passed)
        assertTrue(r is DistrictFilter.Result.BlockParseFail)
    }

    /** 통칭 동탄 콜 통과 (suffix 없는 단축 표기) */
    @Test fun 통칭_동탄_도착_통과() {
        val slot = slotKeys(Triple("화성", null, "동탄1동"))
        val screen = screenWith("/ 동탄 / *")
        val r = DistrictFilter.check(screen, slot)
        assertTrue("결과: $r", r.passed)
    }

    /** 시퀀스 도중 다른 콜로 바뀜 시뮬레이션 — 같은 필터 함수 호출 */
    @Test fun 시퀀스_도중_다른_콜로_바뀌면_차단() {
        val slot = slotKeys(Triple("화성", null, "동탄1동"))
        val firstCall = screenWith("/ 동탄 / *")
        val secondCall = screenWith("/ 강남역삼동 / *")
        val r1 = DistrictFilter.check(firstCall, slot)
        val r2 = DistrictFilter.check(secondCall, slot)
        assertTrue("first: $r1", r1.passed)
        assertFalse("second: $r2", r2.passed)
    }

    /** 출발지 오인 방지 — 도착 영역 외 텍스트는 매칭에서 제외 */
    @Test fun 출발지에_매칭_가능한_동이_있어도_도착지_기준만() {
        // 출발은 동탄, 도착은 강남
        val slot = slotKeys(Triple("화성", null, "동탄면"))
        val depRow = n(null, BBox(0, 100, 1080, 180), listOf(
            n("출발", BBox(20, 120, 80, 160)),
            n("/ 동탄 / *", BBox(120, 120, 600, 160))
        ))
        val arrLabel = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260))
        ))
        val arrValue = n(null, BBox(0, 290, 1080, 370), listOf(
            n("서명", BBox(20, 310, 80, 350)),
            n("/ 강남역삼동 / *", BBox(120, 310, 800, 350))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(depRow, arrLabel, arrValue))
        val r = DistrictFilter.check(root, slot)
        assertFalse("결과: $r — 도착이 강남이므로 차단되어야 함", r.passed)
    }
}
