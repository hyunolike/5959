package com.ogu.shared.config

import io.lettuce.core.ClientOptions
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Lettuce 클라이언트 설정(004 research R5).
 *
 * 기본값(`ACCEPT_COMMANDS`)은 연결이 끊긴 동안 명령을 쌓아 두고 명령 타임아웃(1초)까지 기다린다. Redis는 실시간 신호만
 * 나르므로 끊긴 동안의 명령은 바로 거절해(`REJECT_COMMANDS`) 호출한 쪽이 기다리지 않게 한다. 자동 재연결은 그대로 켜 둔다.
 */
@Configuration(proxyBeanMethods = false)
class RedisClientConfig {
    @Bean
    fun rejectCommandsWhileDisconnected(): LettuceClientOptionsBuilderCustomizer =
        LettuceClientOptionsBuilderCustomizer { builder ->
            builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        }
}
