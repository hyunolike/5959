package com.ogu.member.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `ogu.auth` 설정. OAuth 설정은 외부 로그인 스토리(US3)에서 이 클래스에 추가한다.
 */
@ConfigurationProperties("ogu.auth")
data class AuthProperties(
    val jwt: Jwt,
    val session: Session,
    /** BFF가 `X-Ogu-Bff-Key`로 보내는 비밀 값. 비어 있으면 어떤 요청의 `X-Ogu-Client-Ip`도 믿지 않는다. */
    val bffKey: String = "",
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
}
