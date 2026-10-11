package com.ogu.member.application

import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.oauth.OAuthProviderClient
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration

class OAuthDisabledProviderTest {
    @Test
    fun `US3-AC1 비활성화한 카카오는 외부 제공자를 호출하기 전에 거절한다`() {
        val client = Mockito.mock(OAuthProviderClient::class.java)
        Mockito.`when`(client.provider).thenReturn(OAuthProvider.KAKAO)
        val registrar = Mockito.mock(OAuthMemberRegistrar::class.java)
        val callback = "https://ogu.example.com/api/auth/oauth/kakao/callback"
        val properties =
            AuthProperties(
                jwt = AuthProperties.Jwt("test-secret", Duration.ofMinutes(15)),
                session = AuthProperties.Session(Duration.ofDays(14), Duration.ofDays(30), Duration.ofSeconds(30)),
                oauth =
                    AuthProperties.OAuth(
                        allowedRedirectUris = listOf(callback),
                        kakao = AuthProperties.Kakao(enabled = false),
                    ),
            )
        val service = OAuthLoginService(listOf(client), registrar, properties)

        assertThatThrownBy { service.login("kakao", "valid-code", callback, null) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_REQUEST)
        Mockito.verify(client, Mockito.never()).exchange(Mockito.anyString(), Mockito.anyString(), Mockito.isNull())
        Mockito.verifyNoInteractions(registrar)
    }
}
