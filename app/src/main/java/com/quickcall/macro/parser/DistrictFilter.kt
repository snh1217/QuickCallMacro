package com.quickcall.macro.parser

/**
 * 도착지 필터 검사 — 안드로이드 의존성 없음 (단위 테스트 친화).
 *
 *  활성 슬롯 키 셋과 도착지 노드 트리를 받아서 통과/차단 결정.
 *
 *  정책:
 *   - slotKeys 가 비어있으면 → BlockEmptySlot (활성 슬롯 있는데 비어있음 = 안전 차단)
 *   - 도착지 추출 실패 → BlockParseFail (안전 차단)
 *   - 추출 후보의 키 union 과 slot 교집합 비어있으면 → BlockNoMatch
 *   - 그 외 → Pass
 *
 *  CallMacroService 는 추가로:
 *   - activeSlotId == 0 (사용 안 함) → 호출 자체 안 함, 모든 콜 통과
 */
object DistrictFilter {

    sealed class Result {
        abstract val passed: Boolean
        abstract val tier: String

        data class Pass(
            override val tier: String,
            val tokens: List<String>,
            val matchedKeys: Set<String>
        ) : Result() {
            override val passed: Boolean = true
        }

        object BlockEmptySlot : Result() {
            override val passed: Boolean = false
            override val tier: String = "차단:슬롯비어있음"
        }

        data class BlockParseFail(override val tier: String = "차단:추출실패") : Result() {
            override val passed: Boolean = false
        }

        data class BlockNoMatch(
            override val tier: String,
            val tokens: List<String>,
            val destKeys: Set<String>
        ) : Result() {
            override val passed: Boolean = false
        }
    }

    fun check(root: ParseNode, slotKeys: Set<String>): Result {
        if (slotKeys.isEmpty()) return Result.BlockEmptySlot

        val outcome = DestinationParser.parseNode(root)
        if (outcome.tokens.isEmpty()) return Result.BlockParseFail()

        val destKeys = HashSet<String>()
        for (c in outcome.tokens) {
            destKeys.addAll(DistrictKeyGenerator.keysFromDongToken(c))
            destKeys.add(DistrictKeyGenerator.normalizeKey(c, 2))
        }
        val intersection = destKeys.intersect(slotKeys)
        return if (intersection.isEmpty()) {
            Result.BlockNoMatch(outcome.tier, outcome.tokens, destKeys)
        } else {
            Result.Pass(outcome.tier, outcome.tokens, intersection)
        }
    }
}
