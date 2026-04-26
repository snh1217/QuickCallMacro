package com.quickcall.macro

import com.quickcall.macro.parser.BBox
import com.quickcall.macro.parser.DestinationParser
import com.quickcall.macro.parser.DistrictKeyGenerator
import com.quickcall.macro.parser.DongTokenExtractor
import com.quickcall.macro.parser.ParseNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 도착지 추출(DestinationParser) + 정규화/매칭(DistrictKeyGenerator) 단위 테스트.
 *
 * AccessibilityNodeInfo 를 사용하지 않고 ParseNode 트리를 직접 합성해서 검증.
 */
class DestinationMatchingTest {

    // ───────────────────────── 헬퍼 ─────────────────────────

    private fun n(text: String?, bx: BBox = BBox(0, 0, 0, 0), children: List<ParseNode> = emptyList()) =
        ParseNode(text, bx, children)

    private fun row(y: Int, vararg pairs: Pair<String?, IntRange>): List<ParseNode> {
        // pairs[i] = (text, x range)
        return pairs.map { (txt, xr) -> n(txt, BBox(xr.first, y, xr.last, y + 40)) }
    }

    /** 슬롯 등록 항목들 → 키 셋 합산 (테스트용 simulator) */
    private fun slotKeys(vararg entries: Triple<String, String?, String>): Set<String> {
        // entries: (sigunguShort, guShort?, dong)
        val out = HashSet<String>()
        for ((sg, gu, dong) in entries) {
            out.addAll(DistrictKeyGenerator.keysForSlotEntry(sg, gu, dong))
        }
        return out
    }

    private fun pass(destToken: String, slot: Set<String>): Boolean {
        return DistrictKeyGenerator.keysFromDongToken(destToken).intersect(slot).isNotEmpty()
    }

    // ───────────────────────── 추출 테스트 ─────────────────────────

    @Test fun case1_평택진위면_추출_1단() {
        // "도착" 라벨과 "경기 평택시 평택진위면 / 평택진위면 / *" 가 같은 부모
        val arrivalGroup = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260)),
            n("경기 평택시 평택진위면 / 평택진위면 / *", BBox(120, 220, 1000, 260))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(
            n("출발: 충북음성", BBox(20, 100, 600, 140)),
            arrivalGroup
        ))
        val o = DestinationParser.parseNode(root)
        assertEquals("1단:라벨박스", o.tier)
        assertNotNull(o.token)
        assertEquals("평택진위면", o.token)
    }

    @Test fun case2_마곡동_추출_1단() {
        val arrivalGroup = n(null, BBox(0, 300, 1080, 380), listOf(
            n("도착", BBox(20, 320, 80, 360)),
            n("서울 강서구 마곡동 / 마곡동 / *", BBox(120, 320, 900, 360))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(arrivalGroup))
        val o = DestinationParser.parseNode(root)
        assertEquals("1단:라벨박스", o.tier)
        assertEquals("마곡동", o.token)
    }

    @Test fun case3_서초반포동_바로토큰_제외() {
        val arrivalGroup = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260)),
            n("/ 바로/서초반포동 / *", BBox(120, 220, 800, 260))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(arrivalGroup))
        val o = DestinationParser.parseNode(root)
        assertEquals("1단:라벨박스", o.tier)
        assertEquals("서초반포동", o.token)
    }

    @Test fun case4_성수동_헤더와_출발지_오인_방지() {
        // 헤더에 "성수동", 출발지에 "성남동", 도착지에 "성수동" — 도착만 정확히 잡혀야 함
        val header = n("성수동", BBox(20, 50, 200, 90))
        val departureGroup = n(null, BBox(0, 150, 1080, 230), listOf(
            n("출발", BBox(20, 170, 80, 210)),
            n("/ 성남동 / *", BBox(120, 170, 600, 210))
        ))
        val arrivalGroup = n(null, BBox(0, 250, 1080, 330), listOf(
            n("도착", BBox(20, 270, 80, 310)),
            n("/ 성수동 / *", BBox(120, 270, 600, 310))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(header, departureGroup, arrivalGroup))
        val o = DestinationParser.parseNode(root)
        assertEquals("1단:라벨박스", o.tier)
        assertEquals("성수동", o.token)
    }

    @Test fun coordinate_fallback_동작() {
        // 도착 라벨과 도착지 텍스트가 다른 부모 — 후속 형제 박스 (1단) 또는 좌표(2단) 으로 잡힘
        val labelLeft = n("도착", BBox(20, 500, 80, 540))
        val destRight = n("/ 마곡동 / *", BBox(120, 510, 600, 540))
        val labelGroup = n(null, BBox(0, 480, 200, 560), listOf(labelLeft))
        val destGroup = n(null, BBox(100, 490, 800, 560), listOf(destRight))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(labelGroup, destGroup))
        val o = DestinationParser.parseNode(root)
        assertEquals("마곡동", o.token)
        assertTrue("tier=${o.tier}", o.tier.startsWith("1단") || o.tier.startsWith("2단"))
    }

    @Test fun text_slice_3단_폴백() {
        // 라벨 노드는 없고 텍스트 안에 "도착 ... 동" 만 존재
        val root = n("출발 충북음성 도착 서울 강서구 마곡동", BBox(0, 0, 1080, 100))
        val o = DestinationParser.parseNode(root)
        assertEquals("3단:슬라이스", o.tier)
        assertEquals("마곡동", o.token)
    }

    @Test fun 추출_실패_안전_차단() {
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(
            n("아무 관련 없는 텍스트", BBox(20, 100, 500, 140))
        ))
        val o = DestinationParser.parseNode(root)
        assertEquals("실패", o.tier)
        assertNull(o.token)
    }

    @Test fun 도착시간_부분매칭_금지() {
        // "도착시간" 같은 부분 매칭 라벨이 있어도 그쪽에서 동을 추출하면 안 됨
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(
            n(null, BBox(0, 100, 1080, 180), listOf(
                n("도착시간", BBox(20, 120, 200, 160)),
                n("12:34", BBox(220, 120, 300, 160))
            )),
            n(null, BBox(0, 200, 1080, 280), listOf(
                n("도착", BBox(20, 220, 80, 260)),
                n("/ 마곡동 / *", BBox(120, 220, 600, 260))
            ))
        ))
        val o = DestinationParser.parseNode(root)
        assertEquals("1단:라벨박스", o.tier)
        assertEquals("마곡동", o.token)
    }

    // ───────────────────────── 매칭 테스트 ─────────────────────────

    @Test fun match_마곡동_강서마곡동슬롯() {
        val slot = slotKeys(Triple("강서", null, "마곡동"))
        assertTrue(pass("마곡동", slot))
    }

    @Test fun match_마곡동_서초구슬롯_차단() {
        val slot = slotKeys(
            Triple("서초", null, "반포동"),
            Triple("서초", null, "서초동")
        )
        assertTrue(!pass("마곡동", slot))
    }

    @Test fun match_서초반포동_반포동슬롯() {
        val slot = slotKeys(Triple("서초", null, "반포동"))
        assertTrue(pass("서초반포동", slot))
    }

    @Test fun match_서초반포동_서초구만_슬롯() {
        // 서초구 전체(여러 dong) 등록 — 서초 prefix 키만으로도 통과해야 함
        val slot = slotKeys(
            Triple("서초", null, "서초동"),
            Triple("서초", null, "잠원동"),
            Triple("서초", null, "양재동")
        )
        assertTrue(pass("서초반포동", slot))
    }

    @Test fun match_평택진위면_평택진위슬롯() {
        val slot = slotKeys(Triple("평택", null, "진위면"))
        assertTrue(pass("평택진위면", slot))
    }

    @Test fun match_평택진위면_평택시만_슬롯() {
        val slot = slotKeys(
            Triple("평택", null, "팽성읍"),
            Triple("평택", null, "안중읍")
        )
        assertTrue(pass("평택진위면", slot))
    }

    @Test fun match_평택진위면_화성시슬롯_차단() {
        val slot = slotKeys(Triple("화성", null, "송산면"))
        assertTrue(!pass("평택진위면", slot))
    }

    @Test fun match_성수동_성동성수슬롯() {
        val slot = slotKeys(Triple("성동", null, "성수동"))
        assertTrue(pass("성수동", slot))
    }

    @Test fun match_성남동_성동성수슬롯_차단() {
        val slot = slotKeys(Triple("성동", null, "성수동"))
        assertTrue(!pass("성남동", slot))
    }

    @Test fun match_강남역삼동_강남역삼슬롯() {
        val slot = slotKeys(Triple("강남", null, "역삼동"))
        assertTrue(pass("강남역삼동", slot))
    }

    @Test fun match_3단계_용인처인_역북동() {
        val slot = slotKeys(Triple("용인", "처인", "역북동"))
        // 도착지 화면이 "처인역북동" 결합표기로 나올 가능성 (실측 미확인이지만 키 생성은 커버)
        assertTrue(pass("처인역북동", slot))
        assertTrue(pass("용인역북동", slot))
        assertTrue(pass("역북동", slot))
    }

    // ───────────────────────── v1.0.4 신규 케이스 ─────────────────────────

    /** 케이스 A 모사: [도착] 라벨 행 다음 행에 [서명] + 도착지 텍스트 */
    @Test fun caseA_세로라벨그룹_성수동_추출() {
        val labelRow = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260))
        ))
        val valueRow = n(null, BBox(0, 290, 1080, 370), listOf(
            n("서명", BBox(20, 310, 80, 350)),
            n("/ 성수동 / *", BBox(120, 310, 800, 350))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(
            n("출발: 충북음성", BBox(20, 100, 600, 140)),
            labelRow,
            valueRow
        ))
        val o = DestinationParser.parseNode(root)
        assertEquals("성수동", o.token)
        // 1단(라벨박스+형제 박스) 으로 잡혀야 함
        assertTrue("tier=${o.tier}", o.tier.startsWith("1단") || o.tier.startsWith("2단"))
    }

    /** 케이스 B: 처인구 유방동 */
    @Test fun caseB_유방동_세로라벨그룹_추출() {
        val labelRow = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260))
        ))
        val valueRow = n(null, BBox(0, 290, 1080, 370), listOf(
            n("서명", BBox(20, 310, 80, 350)),
            n("/ 유방동 / *", BBox(120, 310, 800, 350))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(labelRow, valueRow))
        val o = DestinationParser.parseNode(root)
        assertEquals("유방동", o.token)
    }

    /** 2글자 동명: 옥수동 */
    @Test fun 짧은동명_옥수동_추출() {
        val arrivalGroup = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260)),
            n("서울 성동구 옥수동 / 옥수동 / *", BBox(120, 220, 800, 260))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(arrivalGroup))
        val o = DestinationParser.parseNode(root)
        assertEquals("옥수동", o.token)
    }

    /** 4단 슬래시 폴백: 라벨 노드가 전혀 없고 평면 텍스트만 */
    @Test fun 슬래시_폴백_4단_추출() {
        val root = n("회사이름퀵 / 안양관양동 / 별표  /  성수동 / *", BBox(0, 0, 1080, 100))
        val o = DestinationParser.parseNode(root)
        // 마지막 매치 채택 → "성수동"
        assertEquals("성수동", o.token)
        assertEquals("4단:슬래시", o.tier)
    }

    /** 메타 토큰 정제: "서명" 라벨 사이에 끼어있어도 동 토큰 추출 성공 */
    @Test fun 메타토큰_정제_서명_라벨() {
        val arrivalGroup = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260)),
            n("서명", BBox(120, 220, 200, 260)),
            n("/", BBox(220, 220, 240, 260)),
            n("성수동", BBox(260, 220, 400, 260)),
            n("/", BBox(420, 220, 440, 260)),
            n("*", BBox(460, 220, 480, 260))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(arrivalGroup))
        val o = DestinationParser.parseNode(root)
        assertEquals("성수동", o.token)
    }

    /** 매칭: 유방동 → 처인구 유방동 슬롯 */
    @Test fun match_유방동_처인유방슬롯() {
        val slot = slotKeys(Triple("용인", "처인", "유방동"))
        assertTrue(pass("유방동", slot))
    }

    /** 매칭: 처인구의 다른 동만 등록된 슬롯 — 결합 표기는 통과, 단일은 차단 */
    @Test fun match_처인구_다른동_슬롯_결합표기만_통과() {
        val slot = slotKeys(
            Triple("용인", "처인", "역북동"),
            Triple("용인", "처인", "남사읍")
        )
        // 결합 표기 "처인유방동" → "처인" 키로 통과
        assertTrue("처인유방동", pass("처인유방동", slot))
        // 결합 표기 "용인유방동" → "용인" 키로 통과
        assertTrue("용인유방동", pass("용인유방동", slot))
        // 단일 "유방동" → "유방" 키만 있고 슬롯에 없음 → 차단 (사용자가 유방동을 명시 등록해야 함)
        assertTrue("유방동(단일) 차단 기대", !pass("유방동", slot))
    }

    // ───────────── v1.0.5 — 통칭 매칭 ─────────────

    @Test fun 통칭_동탄_단독_토큰_추출() {
        val candidates = DongTokenExtractor.extractCandidates(listOf("동탄 / 동탄 / *"))
        assertTrue("candidates=$candidates", "동탄" in candidates)
    }

    @Test fun 통칭_광교_단독_토큰_추출() {
        val candidates = DongTokenExtractor.extractCandidates(listOf("광교 / 광교 / *"))
        assertTrue("candidates=$candidates", "광교" in candidates)
    }

    @Test fun 통칭_위례_단독_토큰_추출() {
        val candidates = DongTokenExtractor.extractCandidates(listOf("위례 / 위례 / *"))
        assertTrue("candidates=$candidates", "위례" in candidates)
    }

    @Test fun 라벨_단어는_후보에서_제외() {
        val candidates = DongTokenExtractor.extractCandidates(listOf("도착 / 출발 / 픽업 / 서명"))
        assertFalse("도착" in candidates)
        assertFalse("출발" in candidates)
        assertFalse("픽업" in candidates)
        assertFalse("서명" in candidates)
    }

    @Test fun 통칭_콜_화성시_슬롯_매칭() {
        val slot = slotKeys(
            Triple("화성", null, "동탄1동"),
            Triple("화성", null, "동탄2동"),
            Triple("화성", null, "동탄면")
        )
        // dest "동탄" → keys = {동탄}, slot keys 에 "동탄" 포함 → 통과
        val candidates = DongTokenExtractor.extractCandidates(listOf("동탄 / 동탄 / *"))
        val destKeys = HashSet<String>()
        for (c in candidates) {
            destKeys.addAll(DistrictKeyGenerator.keysFromDongToken(c))
            destKeys.add(DistrictKeyGenerator.normalizeKey(c, 2))
        }
        assertTrue("destKeys=$destKeys, slot=$slot", destKeys.intersect(slot).isNotEmpty())
    }

    // ───────────── v1.0.5 — 동/읍/면 세부 선택 시뮬레이션 ─────────────

    /** 슬롯에 화성시의 일부 동만 들어간 케이스 시뮬레이션 */
    @Test fun 화성시_동탄만_선택_동탄콜_통과() {
        val slot = slotKeys(
            Triple("화성", null, "동탄1동"),
            Triple("화성", null, "동탄6동"),
            Triple("화성", null, "동탄면")
        )
        val candidates = DongTokenExtractor.extractCandidates(listOf("동탄 / 동탄 / *"))
        val destKeys = HashSet<String>()
        for (c in candidates) {
            destKeys.addAll(DistrictKeyGenerator.keysFromDongToken(c))
            destKeys.add(DistrictKeyGenerator.normalizeKey(c, 2))
        }
        assertTrue(destKeys.intersect(slot).isNotEmpty())
    }

    /** 화성시 동탄만 선택 → 향남읍은 차단 */
    @Test fun 화성시_동탄만_선택_향남콜_차단() {
        val slot = slotKeys(
            Triple("화성", null, "동탄1동"),
            Triple("화성", null, "동탄면")
        )
        // dest "향남읍 / 향남읍 / *" → suffix 토큰 "향남읍" 추출
        val candidates = DongTokenExtractor.extractCandidates(listOf("향남읍 / 향남읍 / *"))
        val destKeys = HashSet<String>()
        for (c in candidates) {
            destKeys.addAll(DistrictKeyGenerator.keysFromDongToken(c))
            destKeys.add(DistrictKeyGenerator.normalizeKey(c, 2))
        }
        // 화성시 슬롯에 "향남" 키가 없어서 차단
        assertFalse("destKeys=$destKeys, slot=$slot", destKeys.intersect(slot).isNotEmpty())
    }

    /** 출발지 오인 방지: 헤더 + 출발 + 도착에 다른 동들 */
    @Test fun 출발지_헤더_도착_세군데_분리() {
        val header = n("성수동 (헤더)", BBox(20, 50, 400, 90))
        val depRow = n(null, BBox(0, 100, 1080, 180), listOf(
            n("출발", BBox(20, 120, 80, 160)),
            n("/ 강북 미아동 / *", BBox(120, 120, 600, 160))
        ))
        val arrLabel = n(null, BBox(0, 200, 1080, 280), listOf(
            n("도착", BBox(20, 220, 80, 260))
        ))
        val arrValue = n(null, BBox(0, 290, 1080, 370), listOf(
            n("서명", BBox(20, 310, 80, 350)),
            n("/ 사근동 / *", BBox(120, 310, 800, 350))
        ))
        val root = n(null, BBox(0, 0, 1080, 1920), listOf(header, depRow, arrLabel, arrValue))
        val o = DestinationParser.parseNode(root)
        assertEquals("사근동", o.token)
    }
}
