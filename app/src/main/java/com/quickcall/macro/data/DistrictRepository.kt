package com.quickcall.macro.data

import android.content.Context
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

    /** "경기/평택시" → "평택", "경기/수원시/영통구" → "영통" 등 (화면 표기 prefix 추출용) */
    fun sigunguPrefix(key: String): String {
        val last = key.substringAfterLast('/')
        return last.replace(Regex("(시|군|구)$"), "")
    }

    /**
     * 사용자 화면 표기 기준 정규화.
     * 숫자 제거 → 앞 2글자 (2글자 미만이면 그대로).
     */
    fun normalizeKey(name: String): String {
        val cleaned = name.replace(Regex("[0-9]"), "")
        return if (cleaned.length >= 2) cleaned.substring(0, 2) else cleaned
    }

    /**
     * 슬롯에 선택된 시군구들 → 매칭에 쓸 정규화 키 집합.
     *  - 동(동/가/로)은 동 이름 자체로 정규화
     *  - 읍/면은 시군구 prefix + 이름 결합 형태로 정규화 (예: 평택+진위면 → 평택)
     */
    fun expandKeysForSelection(selectedSigunguPaths: Set<String>): Set<String> {
        val out = HashSet<String>()
        for (path in selectedSigunguPaths) {
            val dongs = map[path] ?: continue
            val prefix = sigunguPrefix(path)
            for (dong in dongs) {
                val display = if (dong.endsWith("읍") || dong.endsWith("면")) {
                    prefix + dong
                } else {
                    dong
                }
                out.add(normalizeKey(display))
            }
        }
        return out
    }
}
