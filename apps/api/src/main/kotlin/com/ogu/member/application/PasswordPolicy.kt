package com.ogu.member.application

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode

/**
 * 비밀번호 규칙(002 FR-002). 가입과 비밀번호 재설정(015 FR-005)이 함께 쓴다. 규칙이 두 곳에 따로 있으면 어긋난다.
 * 영문과 숫자를 포함한 8~20자이고, bcrypt가 받는 72바이트를 넘지 않아야 한다.
 */
internal object PasswordPolicy {
    private const val MIN_LENGTH = 8
    private const val MAX_LENGTH = 20

    /** bcrypt는 72바이트까지만 받는다. 20자라도 여러 바이트 문자로 채우면 넘을 수 있다. */
    const val BCRYPT_MAX_BYTES = 72

    /** 규칙에 맞지 않으면 `400 INVALID_REQUEST`다. */
    fun validate(password: String) {
        val valid =
            password.length in MIN_LENGTH..MAX_LENGTH &&
                password.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                password.any { it in '0'..'9' } &&
                password.toByteArray(Charsets.UTF_8).size <= BCRYPT_MAX_BYTES
        if (!valid) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호는 영문과 숫자를 포함해 8~20자로 입력해 주세요.")
        }
    }
}

/** 이메일의 모양(002 FR-001). 가입과 재설정 요청이 함께 쓴다. */
internal object EmailFormat {
    private const val MAX_LENGTH = 254

    /** RFC 5322 간이 형식: 공백과 `@`가 없는 로컬 부분, `@`, 점이 하나 이상 있는 도메인. */
    private val PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** [email]은 정규화한 값이다. 모양이 아니면 `400 INVALID_REQUEST`다. */
    fun validate(email: String) {
        if (email.length > MAX_LENGTH || !PATTERN.matches(email)) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "이메일 형식이 올바르지 않습니다.")
        }
    }
}
