package com.ogu.shared.mail

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `ogu.mail` 설정(015 research R1). [enabled]가 false면(기본) 메일을 보내는 기능이 모두 꺼진다.
 *
 * Boot의 `spring.mail.*`를 쓰지 않는다. 그 자동 설정은 메일 헬스 지표를 함께 넣어, 발송 서비스가 내려가면
 * `/actuator/health`(배포 롤백의 기준)가 DOWN이 된다.
 */
@ConfigurationProperties("ogu.mail")
data class MailProperties(
    val enabled: Boolean = false,
    /** 보내는 주소. `오구오구 <no-reply@example.com>` 꼴도 된다. */
    val from: String = "",
    val smtp: Smtp = Smtp(),
) {
    data class Smtp(
        val host: String = "",
        val port: Int = 587,
        val username: String = "",
        val password: String = "",
        /** 연결, 읽기, 쓰기에 각각 거는 시간. */
        val timeout: Duration = Duration.ofSeconds(10),
    )
}
