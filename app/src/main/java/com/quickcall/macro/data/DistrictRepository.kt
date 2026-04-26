package com.quickcall.macro.data

import android.content.Context
import com.quickcall.macro.parser.DistrictKeyGenerator
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 행정구역 데이터 저장소.
 *
 * 데이터 출처: 법정동코드 전체자료 (FinanceData gist)
 * → scripts/build-districts.js 로 가공된 assets/districts.json 사용
 *
 * JSON 구조:
 *  { "{시도단축}/{시군구}[/구]": ["동/읍/면", ...], ... }
 *
 * 매칭 규칙 (사용자 화면 표기 기준):
 *  - 동: 화면 표기 = 동 이름 그대로 (예: "마곡동")
 *  - 읍/면: 화면 표기 = {시군구이름에서 시/군/구 제거} + 읍면 이름 (예: "평택진위면")
 *  - 정규화: 숫자 제거 → 앞 2글자 (2글자 미만이면 전체)
 */
object DistrictRepository {

    @Volatile
    private var loaded = false
    private val map: MutableMap<String, List<String>> = LinkedHashMap()

    /** 시도 그룹 순서 (화면 표시용) */
    private val sidoOrder = listOf(
        "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
        "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"
    )

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadFromAssets(ctx)
            loaded = true
        }
    }

    private fun loadFromAssets(ctx: Context) {
        val sb = StringBuilder()
        ctx.assets.open("districts.json").use { input ->
            BufferedReader(InputStreamReader(input, "UTF-8")).use { r ->
                var line = r.readLine()
                while (line != null) { sb.append(line); line = r.readLine() }
            }
        }
        val json = JSONObject(sb.toString())
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr: JSONArray = json.getJSONArray(k)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            map[k] = list
        }
    }

    /** 모든 시군구 키 (정렬됨) */
    fun allKeys(): List<String> = map.keys.toList()

    /** 시도별 그룹: Map<시도, List<시군구 path>> */
    fun groupedBySido(): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (s in sidoOrder) out[s] = mutableListOf()
        for (k in map.keys) {
            val sido = k.substringBefore('/')
            out.getOrPut(sido) { mutableListOf() }.add(k)
        }
        // 각 그룹 내 정렬
        for ((_, v) in out) v.sortWith(compareBy { it })
        return out
    }

    /** 시군구 경로의 동/읍/면 리스트 */
    fun getDongs(districtKey: String): List<String> = map[districtKey] ?: emptyList()

    /** 시군구 경로 → 화면 표시용 이름 (마지막 토큰) */
    fun displayName(key: String): String = key.substringAfterLast('/')

    /** 시군구 경로 → 동/읍/면 리스트 별칭 (외부에서 명확한 이름 사용) */
    fun getDongsForSigungu(sigunguKey: String): List<String> = getDongs(sigunguKey)

    /**
     * 슬롯의 정규화 키 셋 — 선택된 동/읍/면만 기반으로 생성.
     * 비활성 시군구(동 0개)는 키에 안 들어감.
     */
    fun normalizedKeysForSlot(slot: DistrictSlot): Set<String> {
        val out = HashSet<String>()
        for ((path, selectedDongs) in slot.selectedDongsBySigungu) {
            if (selectedDongs.isEmpty()) continue
            val parts = path.split('/')
            val sigungu = parts.getOrNull(1) ?: continue
            val gu = parts.getOrNull(2)
            val sigunguShort = DistrictKeyGenerator.stripAdminSuffix(sigungu)
            val guShort = gu?.let { DistrictKeyGenerator.stripAdminSuffix(it) }
            for (dong in selectedDongs) {
                out.addAll(DistrictKeyGenerator.keysForSlotEntry(sigunguShort, guShort, dong))
            }
        }
        return out
    }

    /**
     * 슬롯에 선택된 시군구들 → 매칭에 쓸 키 집합.
     *
     * 시군구 path 의 마지막 행정 토큰을 단축형(시/군/구 접미사 제거)으로 만들고,
     * 3단계(시/구)면 시 단축 + 구 단축 둘 다 사용해서
     * 각 동/읍/면마다 keysForSlotEntry 로 다중 키 생성.
     *
     * 예) "경기/용인시/처인구" + 그 안의 "역북동"
     *     → sigunguShort="용인", guShort="처인" → {"역북","처인역북","처인","용인역북","용인"}
     */
    fun expandKeysForSelection(selectedSigunguPaths: Set<String>): Set<String> {
        val out = HashSet<String>()
        for (path in selectedSigunguPaths) {
            val parts = path.split('/')
            // parts[0] = 시도단축, parts[1] = 시군구, parts.getOrNull(2) = 구
            val sigungu = parts.getOrNull(1) ?: continue
            val gu = parts.getOrNull(2)
            val sigunguShort = DistrictKeyGenerator.stripAdminSuffix(sigungu)
            val guShort = gu?.let { DistrictKeyGenerator.stripAdminSuffix(it) }

            val dongs = map[path] ?: emptyList()
            for (dong in dongs) {
                out.addAll(DistrictKeyGenerator.keysForSlotEntry(sigunguShort, guShort, dong))
            }
            // 동이 하나도 없는 시군구라도 시군구/구 단축 키는 추가 (사용자가 시 전체 선택의 의미)
            if (dongs.isEmpty()) {
                if (sigunguShort.isNotEmpty()) out.add(DistrictKeyGenerator.take(sigunguShort, 2))
                if (guShort?.isNotEmpty() == true) out.add(DistrictKeyGenerator.take(guShort, 2))
            }
        }
        return out
    }
}
