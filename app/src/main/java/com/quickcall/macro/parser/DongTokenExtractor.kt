package com.quickcall.macro.parser

/**
 * 텍스트 조각 리스트에서 도착지 동/읍/면 토큰 추출.
 *
 * 우선순위:
 *  1) 슬래시 패턴: `/ {dong} /` 또는 `/ {dong}` 끝 — 가장 안정적
 *  2) 토큰 패턴: 텍스트 어디든 동/읍/면/가/로 로 끝나는 2글자 이상 토큰
 *
 * 메타 토큰 (도착지 아닌 표현)은 제외.
 */
object DongTokenExtractor {

    /** 정확 일치하면 메타로 간주 */
    private val META_EXACT = setOf(
        "도착", "출발", "*", "-", "/", "·",
        "신용", "현금", "미터", "외상", "선결제", "선불", "착불"
    )

    /** 시작이 이 단어로 시작하면 메타로 간주 */
    private val META_PREFIXES = listOf(
        "바로", "직접", "픽업", "현장", "즉시", "서명", "콜픽업"
    )

    private val SLASH_REGEX = Regex(
        """/\s*([가-힣A-Za-z0-9]+(?:동|읍|면|가|로))\s*(?:/|$)"""
    )
    private val TOKEN_REGEX = Regex(
        """([가-힣A-Za-z0-9]{2,}(?:동|읍|면|가|로))"""
    )
    private val PHONE_LIKE = Regex("""^[0-9-]+$""")

    fun isMetaToken(tok: String): Boolean {
        if (tok.length < 2) return true
        if (tok in META_EXACT) return true
        if (PHONE_LIKE.matches(tok)) return true
        for (p in META_PREFIXES) if (tok.startsWith(p)) return true
        return false
    }

    /**
     * 주어진 텍스트 조각들에서 첫 동/읍/면 토큰 추출.
     * 슬래시 패턴 우선, 없으면 일반 토큰 매칭.
     */
    fun extract(texts: List<String>): String? {
        if (texts.isEmpty()) return null
        val joined = texts.joinToString(" ").trim()
        if (joined.isEmpty()) return null

        // 1) 슬래시 패턴 — 결합본에서 검색
        for (m in SLASH_REGEX.findAll(joined)) {
            val tok = m.groupValues[1]
            if (!isMetaToken(tok)) return tok
        }
        // 2) 토큰 패턴 — 각 조각에서 순차 검색
        for (t in texts) {
            for (m in TOKEN_REGEX.findAll(t)) {
                val tok = m.groupValues[1]
                if (!isMetaToken(tok)) return tok
            }
        }
        return null
    }
}
