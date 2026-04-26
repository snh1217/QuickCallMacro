package com.quickcall.macro.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 구 필터 슬롯 1개.
 *
 *  selectedDongsBySigungu:
 *   - Key: 시군구 경로 ("경기/화성시", "경기/용인시/처인구")
 *   - Value: 해당 시군구에서 선택된 동/읍/면 이름 셋
 *           빈 셋이면 시군구 자체가 비활성 (체크 해제 상태)
 *           해당 시군구의 모든 동을 다 포함하면 "전체 선택" 상태
 */
data class DistrictSlot(
    val id: Int,
    val name: String,
    val selectedDongsBySigungu: Map<String, Set<String>>
) {
    /** 활성 시군구 갯수 (선택된 동이 1개 이상인 것만) */
    fun activeSigunguCount(): Int = selectedDongsBySigungu.count { it.value.isNotEmpty() }

    /** 활성 동/읍/면 총 갯수 */
    fun activeDongCount(): Int = selectedDongsBySigungu.values.sumOf { it.size }

    fun isEmpty(): Boolean = activeDongCount() == 0

    fun toJson(): String {
        val obj = JSONObject()
        for ((path, dongs) in selectedDongsBySigungu) {
            if (dongs.isEmpty()) continue
            val arr = JSONArray()
            for (d in dongs.sorted()) arr.put(d)
            obj.put(path, arr)
        }
        return obj.toString()
    }

    companion object {
        fun fromJson(id: Int, name: String, json: String?): DistrictSlot {
            if (json.isNullOrBlank()) return DistrictSlot(id, name, emptyMap())
            return try {
                val obj = JSONObject(json)
                val map = LinkedHashMap<String, Set<String>>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = obj.getJSONArray(k)
                    val s = LinkedHashSet<String>(arr.length())
                    for (i in 0 until arr.length()) s.add(arr.getString(i))
                    map[k] = s
                }
                DistrictSlot(id, name, map)
            } catch (_: Throwable) {
                DistrictSlot(id, name, emptyMap())
            }
        }
    }
}
