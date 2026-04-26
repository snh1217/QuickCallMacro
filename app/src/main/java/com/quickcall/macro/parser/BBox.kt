package com.quickcall.macro.parser

/**
 * 안드로이드 의존성 없는 좌표 박스. 단위 테스트에서 직접 생성 가능.
 */
data class BBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}
