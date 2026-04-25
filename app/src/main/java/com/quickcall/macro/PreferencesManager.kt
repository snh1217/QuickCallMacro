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
