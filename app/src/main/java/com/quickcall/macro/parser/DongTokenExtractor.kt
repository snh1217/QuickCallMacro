package com.quickcall.macro.parser

/**
 * 텍스트 조각 리스트에서 도착지 후보 토큰 추출.
 *
 *  v1.0.5: 후보 리스트 반환 (3-tier).
 *   - Tier 1: 동/읍/면/리/가/로 접미사 토큰 (가장 강한 후보)
 *   - Tier 2: 슬래시 패턴 안의 한글 토큰 ("동탄 / 동탄 / *" — 통칭/택지명)
 *   - Tier 3: 일반 한글 토큰 (2~5자) — 라벨/메타 단어 제외
 *
 *  매칭 단계는 슬롯 키 셋 교집합으로 자연 차단.
 *  잘못된 후보가 섞여도 슬롯에 없는 키면 통과되지 않음.
 */
object DongTokenExtractor {

    /** 정확 일치 메타 (라벨, 결제수단, 동작 라벨 등) */
    val META_EXACT = setOf(
        "도착", "출발", "서명", "픽업", "의뢰", "물품", "차량",
        "탁송료", "요금", "구분", "형태", "적요", "상세", "인수증",
        "배송", "배차", "보통", "오토", "편도", "완료", "취소", "확정",
        "신용", "현금", "미터", "외상", "선결제", "선불", "착불",
        "*", "-", "/", "·", ":", ";"
    )

    /** 시작 단어가 일치하면 메타 (단, 동/읍/면 접미사가 있으면 통과) */
    private val META_PREFIXES = listOf(
        "바로", "직접", "현장", "즉시", "콜픽업"
    )

    /** 동 접미사 — 짧은 동명 보호용 */
    private val DONG_SUFFIXES = listOf("동", "읍", "면", "리", "가", "로")

    private val SUFFIX_TOKEN_REGEX = Regex(
        """[가-힣]{2,}(?:동|읍|면|리|가|로)"""
    )
    private val SLASH_BARE_REGEX = Regex(
        """(?:^|/)\s*([가-힣]{2,5})\s*(?=\s*/|\s*\*|\s*$)"""
    )
    private val GENERIC_TOKEN_REGEX = Regex(
        """[가-힣]{2,5}"""
    )
    private val PHONE_LIKE = Regex("""^[0-9-]+$""")

    fun isAdminLabel(token: String): Boolean = token in META_EXACT

    fun isMetaToken(tok: String): Boolean {
        if (tok.length < 2) return true
        if (tok in META_EXACT) return true
        if (PHONE_LIKE.matches(tok)) return true
        // 동/읍/면 접미사로 끝나면 메타 prefix 검사 면제 (짧은 동명 보호)
        if (DONG_SUFFIXES.any { tok.endsWith(it) }) return false
        for (p in META_PREFIXES) if (tok.startsWith(p)) return true
        return false
    }

    /** v1.0.4 backward-compat — 첫 후보 반환 */
    fun extract(texts: List<String>): String? = extractCandidates(texts).firstOrNull()

    /**
     * 3-tier 후보 추출. 결과는 신뢰도 순서.
     */
    fun extractCandidates(texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        val joined = texts.joinToString(" ").trim()
        if (joined.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()

        // Tier 1: 접미사 토큰 (동/읍/면/리/가/로)
        for (m in SUFFIX_TOKEN_REGEX.findAll(joined)) {
            val tok = m.value
            if (!isMetaToken(tok)) out.add(tok)
        }

        // Tier 2: 슬래시 사이 한글 토큰 (통칭/택지명)
        for (m in SLASH_BARE_REGEX.findAll(joined)) {
            val tok = m.groupValues[1]
            if (!isMetaToken(tok)) out.add(tok)
        }

        // Tier 3: 일반 한글 2~5자 (보조 후보)
        for (m in GENERIC_TOKEN_REGEX.findAll(joined)) {
            val tok = m.value
            if (!isMetaToken(tok)) out.add(tok)
        }

        return out.toList()
    }
}
