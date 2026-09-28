package com.ogu.member.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

class UniqueConstraintsTest {
    @Test
    fun `위반한 제약 이름이 같을 때만 그 제약 위반으로 본다`() {
        val e = violation("member_email_key")

        assertThat(UniqueConstraints.isViolated(e, UniqueConstraints.MEMBER_EMAIL)).isTrue()
        assertThat(UniqueConstraints.isViolated(e, UniqueConstraints.MEMBER_NICKNAME_KEY)).isFalse()
    }

    @Test
    fun `원인 사슬 안쪽의 제약 이름도 찾는다`() {
        val e = DataIntegrityViolationException("outer", RuntimeException("wrap", violation("member_nickname_key_key")))

        assertThat(UniqueConstraints.isViolated(e, UniqueConstraints.MEMBER_NICKNAME_KEY)).isTrue()
    }

    @Test
    fun `제약 이름을 알 수 없으면 어떤 제약 위반으로도 보지 않는다`() {
        val noName = DataIntegrityViolationException("x", ConstraintViolationException("m", SQLException("m"), null))
        val noCause = DataIntegrityViolationException("x")

        assertThat(UniqueConstraints.isViolated(noName, UniqueConstraints.MEMBER_EMAIL)).isFalse()
        assertThat(UniqueConstraints.isViolated(noCause, UniqueConstraints.MEMBER_EMAIL)).isFalse()
    }

    @Test
    fun `제약 이름은 V2 마이그레이션의 이름과 같다`() {
        assertThat(UniqueConstraints.MEMBER_EMAIL).isEqualTo("member_email_key")
        assertThat(UniqueConstraints.MEMBER_NICKNAME_KEY).isEqualTo("member_nickname_key_key")
        assertThat(UniqueConstraints.OAUTH_IDENTITY_PROVIDER_USER).isEqualTo("oauth_identity_provider_user_key")
    }

    private fun violation(constraint: String) =
        DataIntegrityViolationException(
            "could not execute statement",
            ConstraintViolationException("duplicate key", SQLException("duplicate key", "23505"), constraint),
        )
}
