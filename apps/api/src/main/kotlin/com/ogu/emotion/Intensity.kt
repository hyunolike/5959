package com.ogu.emotion

/** 감정 강도. 몬스터 최대 HP를 정한다. */
enum class Intensity(
    val maxHp: Int,
) {
    LOW(10),
    MEDIUM(20),
    HIGH(30),
}
