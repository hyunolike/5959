package com.ogu.member.domain

import java.util.Locale

/** 외부 계정 제공자. 경로 변수는 소문자(`kakao`, `google`), 저장 값은 대문자다. */
enum class OAuthProvider(
    val authMethod: AuthMethod,
) {
    KAKAO(AuthMethod.KAKAO),
    GOOGLE(AuthMethod.GOOGLE),
    ;

    val pathValue: String = name.lowercase(Locale.ROOT)

    companion object {
        /** 계약의 경로 변수(`kakao`, `google`)만 받는다. 대소문자가 다르거나 모르는 값이면 null. */
        fun fromPath(value: String): OAuthProvider? = entries.firstOrNull { it.pathValue == value }
    }
}
