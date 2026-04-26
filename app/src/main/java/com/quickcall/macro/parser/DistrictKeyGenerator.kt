package com.quickcall.macro.parser

/**
 * 정규화 키 생성기 (안드로이드 의존성 없음 — 단위 테스트 친화).
 *
 * 슬롯에 등록된 동/읍/면이 화면 표기에서 어떻게 보일지를 폭넓게 커버하기 위해
 * 한 등록 항목당 여러 키를 생성한다.
 *
 *  ## 슬롯 등록 항목 → 키들
 *
 *  2단 (시도/시군구/동) — 예: 강서구 마곡동
 *    - "마곡"               (동 앞2)
 *    - "강서마곡"            (시군구단축 + 동단축, 앞 4글자)
 *    - "강서"               (시군구단축)
 *
 *  2단 (시도/시군구/읍or면) — 예: 평택시 진위면
 *    - "진위", "평택진위", "평택"
 *
 *  3단 (시도/시/구/동) — 예: 용인시 처인구 역북동
 *    - "역북", "처인역북", "처인", "용인역북", "용인"
 *
 *  ## 도착지 화면 토큰 → 키들
 *
 *  단일 토큰 (마곡동, 성수동) → 앞 2 ("마곡", "성수")
 *  결합 토큰 (서초반포동) → {앞2, 접미사제거전체, 분할 앞+뒤}
 *  일반 패턴 (서울 서초구 반포동) → 토큰 분리 후 각각 + 결합
 */
object DistrictKeyGenerator {

    /** 동/읍/면/가/로 접미사 */
    private val SUFFIXES = listOf("동", "읍", "면", "가", "로")

    /** 시군구 행정 접미사 */
    private val ADMIN_SUFFIXES = listOf("특별시", "광역시", "특별자치시", "특별자치도", "도", "시", "군", "구")

    fun stripDigits(s: String): String = s.replace(Regex("[0-9]"), "")

    /** 동 이름에서 접미사 제거. 없으면 그대로 */
    fun stripDongSuffix(s: String): String {
        for (suf in SUFFIXES) if (s.endsWith(suf)) return s.dropLast(suf.length)
        return s
    }

    /** 시/군/구/도 등 행정 접미사 제거. 가장 긴 접미사부터 */
    fun stripAdminSuffix(s: String): String {
        val sorted = ADMIN_SUFFIXES.sortedByDescending { it.length }
        for (suf in sorted) if (s.endsWith(suf)) return s.dropLast(suf.length)
        return s
    }

    /** 안전 substring: 길이 미만이면 그대로 */
    fun take(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, n)

    /** 정규화: 숫자 제거 후 앞 N글자 (기본 2) */
    fun normalizeKey(name: String, n: Int = 2): String {
        val cleaned = stripDigits(name)
        return take(cleaned, n)
    }

    /**
     * 슬롯 등록 한 항목 → 후보 키 셋
     *
     * @param sigunguShort  시군구 단축형. 시/군의 경우 "평택", "용인" 등
     * @param guShort       3단계의 구 단축형. "처인" 등. 2단계면 null
     * @param dong          원본 동/읍/면 이름. "마곡동", "진위면", "역북동"
     */
    fun keysForSlotEntry(sigunguShort: String, guShort: String?, dong: String): Set<String> {
        val out = HashSet<String>()
        val dongStripped = stripDongSuffix(stripDigits(dong))
        if (dongStripped.isNotEmpty()) out.add(take(dongStripped, 2))

        val sg = stripDigits(sigunguShort)
        if (guShort != null) {
            val g = stripDigits(guShort)
            if (g.isNotEmpty()) {
                out.add(take(g, 2))
                if (dongStripped.isNotEmpty()) {
                    out.add(take(g + dongStripped, 4))
                }
            }
            if (sg.isNotEmpty()) {
                out.add(take(sg, 2))
                if (dongStripped.isNotEmpty()) {
                    out.add(take(sg + dongStripped, 4))
                }
            }
        } else {
            if (sg.isNotEmpty()) {
                out.add(take(sg, 2))
                if (dongStripped.isNotEmpty()) {
                    out.add(take(sg + dongStripped, 4))
                }
            }
        }
        return out
    }

    /**
     * 도착지 화면 토큰(단일 동 표기) → 후보 키 셋
     * 예: "서초반포동" → {"서초", "서초반포", "반포"}
     *     "마곡동" → {"마곡"}
     *     "평택진위면" → {"평택", "평택진위", "진위"}
     */
    fun keysFromDongToken(token: String): Set<String> {
        val out = HashSet<String>()
        val cleaned = stripDigits(token)
        val core = stripDongSuffix(cleaned)
        if (core.isEmpty()) return out

        // 항상: 앞 2글자
        out.add(take(core, 2))
        // 접미사 제거한 본체 (전체)
        if (core.length >= 3) out.add(core)

        // 결합 표기 분할: 4자 이상이면 앞2 + 뒤2 도 추가
        if (core.length >= 4) {
            val front = take(core, 2)
            val back = core.substring(2, minOf(core.length, 4))
            out.add(front)
            out.add(back)
        }
        // 5자(시2 + 동3) 패턴: 앞2 + 뒤3
        if (core.length == 5) {
            val front = take(core, 2)
            val back = core.substring(2, 5)
            out.add(front)
            out.add(back)
        }
        return out
    }

    /**
     * 도착지 텍스트가 일반 패턴 ("서울 서초구 반포동") 형태면 분리 후 각각 키 생성.
     * 단일 토큰이면 keysFromDongToken 만 호출.
     */
    fun keysFromDestinationText(text: String): Set<String> {
        val out = HashSet<String>()
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }

        // 마지막 토큰이 동/읍/면인 경우 — 그 토큰에서 키 생성
        val last = tokens.lastOrNull()
        if (last != null && SUFFIXES.any { last.endsWith(it) }) {
            out.addAll(keysFromDongToken(last))
        }
        // 시군구/구 토큰 (시|군|구로 끝남) 도 키로 추가
        for (t in tokens) {
            val tt = stripDigits(t)
            if (tt.endsWith("시") || tt.endsWith("군") || tt.endsWith("구")) {
                val short = stripAdminSuffix(tt)
                if (short.isNotEmpty()) out.add(take(short, 2))
            }
        }
        return out
    }
}
