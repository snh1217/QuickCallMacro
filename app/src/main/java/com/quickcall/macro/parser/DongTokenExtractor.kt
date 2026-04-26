package com.quickcall.macro.parser

/**
 * 텍스트 조각 리스트에서 도착지 동/읍/면 토큰 추출.
 *
 *  v1.0.4 정책 변경:
 *   - 메타 토큰 검출 시 전체 결과를 버리던 기존 정책 → "메타 토큰만 제외하고 남은 후보에서 동 찾기"
 *   - 동/읍/면/가/로 접미사로 끝나는 토큰은 메타 필터를 자동 통과 (짧은 동명 보호)
 *
 *  추출 우선순위:
 *   1) 슬래시 패턴: `/ {dong} /` 또는 `/ {dong}` 끝 — 가장 안정적
 *   2) 일반 토큰 패턴: 어디든 동/읍/면/가/로 로 끝나는 2글자 이상 토큰
 *
 *  메타 토큰:
 *   - 정확 일치: 도착, 출발, 서명, *, /, 결제 수단 등
 *   - 시작 단어: 바로/직접/픽업/현장/즉시/콜픽업 (단, 동 접미사 있으면 통과)
 */
object DongTokenExtractor {

    private val META_EXACT = setOf(
        "도착", "출발", "서명", "픽업", "의뢰", "물품", "차량",
        "탁송료", "요금", "구분", "형태", "적요", "상세", "인수증",
        "*", "-", "/", "·", ":", ";",
        "신용", "현금", "미터", "외상", "선결제", "선불", "착불"
    )

    private val META_PREFIXES = listOf(
        "바로", "직접", "현장", "즉시", "콜픽업"
    )

    private val DONG_SUFFIXES = listOf("동", "읍", "면", "가", "로")

    private val SLASH_REGEX = Regex(
        """/\s*([가-힣A-Za-z0-9]{2,}(?:동|읍|면|가|로))\s*(?:/|$)"""
    )
    private val TOKEN_REGEX = Regex(
        """([가-힣A-Za-z0-9]{2,}(?:동|읍|면|가|로))"""
    )
    private val PHONE_LIKE = Regex("""^[0-9-]+$""")

    /** 동/읍/면/가/로 로 끝나면 메타 필터 자동 통과 */
    private fun endsWithDongSuffix(tok: String): Boolean =
        DONG_SUFFIXES.any { tok.endsWith(it) }

    fun isMetaToken(tok: String): Boolean {
        if (tok.length < 2) return true
        if (tok in META_EXACT) return true
        if (PHONE_LIKE.matches(tok)) return true
        // 동 접미사 있으면 동명 우선 — 메타 prefix 거부도 무시
        if (endsWithDongSuffix(tok)) return false
        for (p in META_PREFIXES) if (tok.startsWith(p)) return true
        return false
    }

    /**
     * 주어진 텍스트 조각들에서 첫 동/읍/면 토큰 추출.
     * 슬래시 패턴 우선, 없으면 일반 토큰 매칭.
     * 메타 토큰은 토큰 단위로 제외하고 남은 후보를 본다.
     */
    fun extract(texts: List<String>): String? {
        if (texts.isEmpty()) return null
        val joined = texts.joinToString(" ").trim()
        if (joined.isEmpty()) return null

        // 1) 슬래시 패턴 — 결합본 전체에서 모든 매치 후 메타 제외, 첫 비메타 채택
        val slashTokens = SLASH_REGEX.findAll(joined).map { it.groupValues[1] }.toList()
        slashTokens.firstOrNull { !isMetaToken(it) }?.let { return it }

        // 2) 일반 토큰 패턴 — 모든 매치 수집 후 메타 제외, 첫 비메타 채택
        val allTokens = ArrayList<String>(8)
        for (t in texts) {
            for (m in TOKEN_REGEX.findAll(t)) allTokens.add(m.groupValues[1])
        }
        return allTokens.firstOrNull { !isMetaToken(it) }
    }
}
