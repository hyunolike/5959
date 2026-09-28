package com.ogu.member.infrastructure.oauth

import com.ogu.member.domain.OAuthProvider
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FakeOAuthProviderClientTest {
    private val client = FakeOAuthProviderClient(OAuthProvider.KAKAO)

    @Test
    fun `코드에서 사용자 ID와 인증된 이메일을 만든다`() {
        assertThat(client.exchange("fake:user-1:user1@example.com", REDIRECT_URI, null))
            .isEqualTo(OAuthUserInfo("user-1", "user1@example.com", emailVerified = true))
    }

    @Test
    fun `같은 코드는 언제나 같은 사용자다`() {
        assertThat(client.exchange("fake:user-1:-", REDIRECT_URI, null))
            .isEqualTo(client.exchange("fake:user-1:-", REDIRECT_URI, null))
    }

    @Test
    fun `이메일 자리가 -면 이메일 없는 계정이다`() {
        assertThat(client.exchange("fake:user-2:-", REDIRECT_URI, null))
            .isEqualTo(OAuthUserInfo("user-2", null, emailVerified = false))
    }

    @ParameterizedTest
    @ValueSource(strings = ["denied", "fake:", "fake::a@b.c", "other:user:-", "fake:user"])
    fun `denied나 형식에 맞지 않는 코드는 OAUTH_CODE_INVALID`(code: String) {
        assertThatThrownBy { client.exchange(code, REDIRECT_URI, null) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_CODE_INVALID)
    }

    companion object {
        private const val REDIRECT_URI = "http://localhost:3000/api/auth/oauth/kakao/callback"
    }
}
