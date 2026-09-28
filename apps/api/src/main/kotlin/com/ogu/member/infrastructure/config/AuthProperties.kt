package com.ogu.member.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * `ogu.auth` 설정.
 */
@ConfigurationProperties("ogu.auth")
data class AuthProperties(
    val jwt: Jwt,
    val session: Session,
    /** BFF가 `X-Ogu-Bff-Key`로 보내는 비밀 값. 비어 있으면 어떤 요청의 `X-Ogu-Client-Ip`도 믿지 않는다. */
    val bffKey: String = "",
    val oauth: OAuth = OAuth(),
) {
    data class Jwt(
        val secret: String,
        val accessTokenTtl: Duration,
    )

    data class Session(
        val idleTtl: Duration,
        val absoluteTtl: Duration,
        val rotationGrace: Duration,
    )

    /**
     * 외부 계정 로그인(research R4). 제공자 주소는 테스트에서 바꿀 수 있게 설정으로 두고, 기본값은 실제 주소다.
     */
    data class OAuth(
        /** BFF가 보낸 `redirectUri`는 이 목록의 값 하나와 정확히 같아야 한다. */
        val allowedRedirectUris: List<String> = emptyList(),
        val kakao: Kakao = Kakao(),
        val google: Google = Google(),
    )

    data class Kakao(
        val clientId: String = "",
        val clientSecret: String = "",
        val tokenUri: URI = URI.create("https://kauth.kakao.com/oauth/token"),
        val userInfoUri: URI = URI.create("https://kapi.kakao.com/v2/user/me"),
    )

    data class Google(
        val clientId: String = "",
        val clientSecret: String = "",
        val tokenUri: URI = URI.create("https://oauth2.googleapis.com/token"),
        val jwksUri: URI = URI.create("https://www.googleapis.com/oauth2/v3/certs"),
    )
}
