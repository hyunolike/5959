package com.ogu.member.infrastructure.persistence

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException

/**
 * 사전 확인과 저장 사이의 경합으로 유일 제약에 걸렸을 때, 어떤 제약인지 이름으로 가려낸다.
 * 기대한 제약이 아니면 호출한 쪽은 예외를 그대로 다시 던진다(다른 제약 위반을 409로 숨기지 않는다).
 * 이름은 V2__member_auth.sql이 만든 것이다. Hibernate가 PostgreSQL 오류 메시지에서 읽은 이름을 비교한다.
 */
object UniqueConstraints {
    /** 이메일 가입끼리의 부분 유일 인덱스. */
    const val MEMBER_EMAIL = "member_email_key"

    /** `nickname_key VARCHAR(10) UNIQUE`에 PostgreSQL이 붙인 이름. */
    const val MEMBER_NICKNAME_KEY = "member_nickname_key_key"

    /** 한 외부 계정은 한 회원에게만 연결된다. */
    const val OAUTH_IDENTITY_PROVIDER_USER = "oauth_identity_provider_user_key"

    fun isViolated(
        e: DataIntegrityViolationException,
        constraint: String,
    ): Boolean =
        generateSequence<Throwable>(e) { it.cause.takeIf { cause -> cause !== it } }
            .filterIsInstance<ConstraintViolationException>()
            .any { it.constraintName.equals(constraint, ignoreCase = true) }
}
