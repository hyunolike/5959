package com.ogu.member.domain

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import java.util.Locale

/**
 * 닉네임 규칙(FR-008, research R8). 앞뒤 공백을 뺀 뒤 완성형 한글, 영문, 숫자 1~10자만 허용한다.
 * [value]는 입력한 그대로(공백 제거만) 보여 주고, 중복 판단은 소문자로 바꾼 [key]로 한다.
 */
class Nickname private constructor(
    val value: String,
) {
    val key: String = value.lowercase(Locale.ROOT)

    companion object {
        private val PATTERN = Regex("^[가-힣A-Za-z0-9]{1,10}$")

        fun isValid(raw: String): Boolean = PATTERN.matches(raw.trim())

        fun of(raw: String): Nickname {
            val trimmed = raw.trim()
            if (!PATTERN.matches(trimmed)) {
                throw BusinessException(ErrorCode.INVALID_REQUEST, "닉네임은 한글, 영문, 숫자로 1~10자까지 쓸 수 있습니다.")
            }
            return Nickname(trimmed)
        }
    }
}
