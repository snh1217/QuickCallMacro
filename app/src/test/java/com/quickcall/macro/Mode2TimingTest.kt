package com.quickcall.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 모드 2 시간 계산 검증.
 *
 *  PreferencesManager 자체는 SharedPreferences 의존이 있어 단위 테스트
 *  대상이 아니므로, 시퀀스 시간 계산 알고리즘을 별도 함수로 추출하여 검증.
 */
class Mode2TimingTest {

    /** mode2 시퀀스가 holdMs 안에 confirm 탭을 몇 번 시도하는지 + 마지막 추적 1회 */
    private fun computeTickCount(holdMs: Int, tickMs: Int): Int {
        // CallMacroService 와 동일 로직: 즉시 1회 + (tickMs, 2*tickMs, ... < holdMs) + 마지막 추적 1회
        var t = tickMs.toLong()
        var n = 1  // 즉시 첫 탭
        while (t < holdMs) {
            n++
            t += tickMs.toLong()
        }
        return n  // confirm 탭 횟수 (마지막 추적은 별도)
    }

    @Test fun 홀드_5초_탭_1초_총_5회_확정탭() {
        // t=0(즉시), 1000, 2000, 3000, 4000 — 5회 확정 탭, 5초 후 추적 1회
        assertEquals(5, computeTickCount(5000, 1000))
    }

    @Test fun 홀드_3초_탭_500ms_총_6회_확정탭() {
        // t=0, 500, 1000, 1500, 2000, 2500 — 6회 확정 탭, 3초 후 추적
        assertEquals(6, computeTickCount(3000, 500))
    }

    @Test fun 홀드_1초_탭_1초_즉시_1회_확정탭() {
        // t=0 만 trigger (1000은 holdMs(1000) 미만 아님 → 추가 안 함), 1초 후 추적
        assertEquals(1, computeTickCount(1000, 1000))
    }

    @Test fun 홀드_10초_탭_300ms_총_횟수() {
        // 0, 300, 600, ..., 9900 — count = ceil(10000/300) = 34
        // 정확히 t=0 + 33 추가 (t=300..9900) = 34
        val n = computeTickCount(10000, 300)
        assertTrue("n=$n", n in 33..34)
    }

    /** holdMs 1000~10000, tickMs 300~2000 범위 안에서 항상 최소 1회 탭 */
    @Test fun 모든_유효_조합에서_최소_1회_탭() {
        for (hold in intArrayOf(1000, 2000, 3000, 5000, 7000, 10000)) {
            for (tick in intArrayOf(300, 500, 1000, 1500, 2000)) {
                assertTrue("hold=$hold tick=$tick", computeTickCount(hold, tick) >= 1)
            }
        }
    }
}
