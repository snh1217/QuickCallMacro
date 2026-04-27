package com.quickcall.macro

import android.content.Context
import android.content.SharedPreferences

enum class MacroMode { MODE_1_INSTANT_TRACK, MODE_2_HOLD_THEN_TRACK }

/**
 * 사용자 설정값 저장소.
 * - 매크로 ON/OFF
 * - 타겟 텍스트 ("확정추적")
 * - 거리/요금 필터
 * - 동작 모드 (모드 1 즉시 추적 / 모드 2 5초 확정 후 추적)
 */
object PreferencesManager {
    private const val PREF_NAME = "quickcall_prefs"

    private const val KEY_MACRO_ENABLED = "macro_enabled"
    private const val KEY_TARGET_TEXT = "target_text"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_MIN_DISTANCE = "min_distance"
    private const val KEY_MAX_DISTANCE = "max_distance"
    private const val KEY_MIN_FARE = "min_fare"
    private const val KEY_CACHED_X = "cached_x"
    private const val KEY_CACHED_Y = "cached_y"
    private const val KEY_MACRO_MODE = "macro_mode"
    private const val KEY_DEBUG_TOAST = "debug_toast"
    private const val KEY_ACTIVE_SLOT = "active_slot"
    private const val KEY_MIGRATION_V105 = "migration_v105_done"
    const val SLOT_COUNT = 5

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // 매크로 동작 여부
    var enabled: Boolean
        get() = singleton.getBoolean(KEY_MACRO_ENABLED, false)
        set(value) = singleton.edit().putBoolean(KEY_MACRO_ENABLED, value).apply()

    var targetText: String
        get() = singleton.getString(KEY_TARGET_TEXT, "확정추적") ?: "확정추적"
        set(value) = singleton.edit().putString(KEY_TARGET_TEXT, value).apply()

    var filterEnabled: Boolean
        get() = singleton.getBoolean(KEY_FILTER_ENABLED, false)
        set(value) = singleton.edit().putBoolean(KEY_FILTER_ENABLED, value).apply()

    /** 단위: km. 0 이면 무제한 */
    var minDistance: Float
        get() = singleton.getFloat(KEY_MIN_DISTANCE, 0f)
        set(value) = singleton.edit().putFloat(KEY_MIN_DISTANCE, value).apply()

    var maxDistance: Float
        get() = singleton.getFloat(KEY_MAX_DISTANCE, 0f)
        set(value) = singleton.edit().putFloat(KEY_MAX_DISTANCE, value).apply()

    /** 단위: 원. 0 이면 무제한 */
    var minFare: Int
        get() = singleton.getInt(KEY_MIN_FARE, 0)
        set(value) = singleton.edit().putInt(KEY_MIN_FARE, value).apply()

    /** 한 번 찾은 버튼 좌표를 캐싱하여 다음번엔 매칭 없이 즉시 탭 */
    var cachedX: Int
        get() = singleton.getInt(KEY_CACHED_X, -1)
        set(value) = singleton.edit().putInt(KEY_CACHED_X, value).apply()

    var cachedY: Int
        get() = singleton.getInt(KEY_CACHED_Y, -1)
        set(value) = singleton.edit().putInt(KEY_CACHED_Y, value).apply()

    var debugToast: Boolean
        get() = singleton.getBoolean(KEY_DEBUG_TOAST, false)
        set(value) = singleton.edit().putBoolean(KEY_DEBUG_TOAST, value).apply()

    /** 모드 2 홀드 총 시간 (밀리초). 기본 5000, 1000~10000 범위 */
    var mode2HoldDurationMs: Int
        get() = singleton.getInt("mode2_hold_duration_ms", 5000)
        set(value) = singleton.edit().putInt("mode2_hold_duration_ms", value.coerceIn(1000, 10000)).apply()

    /** 모드 2 탭 간격 (밀리초). 기본 1000, 최소 300 */
    var mode2TapIntervalMs: Int
        get() = singleton.getInt("mode2_tap_interval_ms", 1000)
        set(value) = singleton.edit().putInt("mode2_tap_interval_ms", value.coerceAtLeast(300)).apply()

    var macroMode: MacroMode
        get() = try {
            MacroMode.valueOf(
                singleton.getString(KEY_MACRO_MODE, MacroMode.MODE_1_INSTANT_TRACK.name)
                    ?: MacroMode.MODE_1_INSTANT_TRACK.name
            )
        } catch (_: IllegalArgumentException) {
            MacroMode.MODE_1_INSTANT_TRACK
        }
        set(value) = singleton.edit().putString(KEY_MACRO_MODE, value.name).apply()

    /** 0 = 사용 안 함, 1..SLOT_COUNT = 활성 슬롯 */
    var activeSlotId: Int
        get() = singleton.getInt(KEY_ACTIVE_SLOT, 0)
        set(value) = singleton.edit().putInt(KEY_ACTIVE_SLOT, value.coerceIn(0, SLOT_COUNT)).apply()

    fun getSlotName(id: Int): String {
        return singleton.getString("slot_${id}_name", "슬롯 $id") ?: "슬롯 $id"
    }

    fun setSlotName(id: Int, name: String) {
        singleton.edit().putString("slot_${id}_name", name).apply()
    }

    /** v1.0.4 호환 — 시군구 경로 셋 (마이그레이션 후 제거됨) */
    fun getLegacySlotKeys(id: Int): Set<String> {
        return singleton.getStringSet("slot_${id}_keys", emptySet()) ?: emptySet()
    }

    private fun removeLegacySlotKeys(id: Int) {
        singleton.edit().remove("slot_${id}_keys").apply()
    }

    /** v1.0.5 — 시군구 → 선택된 동 JSON */
    fun getSlotSelectionJson(id: Int): String? {
        return singleton.getString("slot_${id}_dongs", null)
    }

    fun setSlotSelectionJson(id: Int, json: String) {
        singleton.edit().putString("slot_${id}_dongs", json).apply()
    }

    /** 마이그레이션 1회 완료 플래그 */
    var migrationV105Done: Boolean
        get() = singleton.getBoolean(KEY_MIGRATION_V105, false)
        set(value) = singleton.edit().putBoolean(KEY_MIGRATION_V105, value).apply()

    /** 마이그레이션: v1.0.4 시군구 셋 → v1.0.5 (시군구 → 모든 동) */
    fun migrateV104ToV105(getDongs: (sigunguPath: String) -> List<String>) {
        if (migrationV105Done) return
        for (id in 1..SLOT_COUNT) {
            val legacy = getLegacySlotKeys(id)
            if (legacy.isEmpty()) continue
            val obj = org.json.JSONObject()
            for (path in legacy) {
                val dongs = getDongs(path)
                if (dongs.isEmpty()) continue
                val arr = org.json.JSONArray()
                for (d in dongs) arr.put(d)
                obj.put(path, arr)
            }
            setSlotSelectionJson(id, obj.toString())
            removeLegacySlotKeys(id)
        }
        migrationV105Done = true
    }

    private lateinit var singleton: SharedPreferences

    fun init(ctx: Context) {
        singleton = prefs(ctx.applicationContext)
    }

    fun clearCachedCoords() {
        singleton.edit()
            .remove(KEY_CACHED_X)
            .remove(KEY_CACHED_Y)
            .apply()
    }
}
