package com.ogu.shared.config

import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 기본 헬스 그룹(`/actuator/health`)에서 Redis 지표를 뺀다(004 research R5).
 *
 * 배포 스크립트는 `/actuator/health`가 UP이 아니면 API를 롤백한다. Redis는 실시간 전달만 맡고 알림은 DB에
 * 저장되므로, Redis가 내려갔다고 API를 롤백하면 안 된다. Spring Boot는 기본 그룹의 구성원을 설정으로 고를 수
 * 없어(언제나 전부) 여기서 기본 그룹만 감싼다. Redis 상태는 `management.endpoint.health.group.realtime`
 * (`/actuator/health/realtime`)으로 따로 본다. 그룹 이름은 지표 이름(`redis`)과 겹칠 수 없다.
 */
@Configuration(proxyBeanMethods = false)
class RedisHealthGroupConfig {
    @Bean
    fun redisExcludedFromPrimaryHealth(): HealthEndpointGroupsPostProcessor =
        HealthEndpointGroupsPostProcessor { groups ->
            HealthEndpointGroups.of(
                PrimaryWithout(groups.primary, REDIS_CONTRIBUTOR),
                groups.names.associateWith { requireNotNull(groups.get(it)) },
            )
        }

    private class PrimaryWithout(
        private val delegate: HealthEndpointGroup,
        private val excluded: String,
    ) : HealthEndpointGroup by delegate {
        override fun isMember(name: String): Boolean = name != excluded && delegate.isMember(name)
    }

    companion object {
        /** Spring Data Redis 헬스 지표의 이름. */
        const val REDIS_CONTRIBUTOR = "redis"
    }
}
