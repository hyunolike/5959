package com.ogu.shared.config

import com.ogu.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.HttpStatus

/**
 * 004 Batch 1(T003, T004): Redis 의존성과 헬스 그룹 설정을 확인한다.
 * Redis는 실시간 전달만 맡으므로 `/actuator/health`(배포 롤백 기준)에서 빼고 `realtime` 그룹으로 따로 본다(research R5).
 * 그룹 이름은 지표 이름(`redis`)과 같을 수 없어(Spring Boot가 기동을 막는다) `realtime`이다.
 * [com.ogu.ContractTests]와 같은 설정이라 같은 애플리케이션 컨텍스트를 함께 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class RedisSetupTests {
    @Autowired
    lateinit var connectionFactory: RedisConnectionFactory

    @Autowired
    lateinit var healthGroups: HealthEndpointGroups

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `테스트 컨텍스트는 Testcontainers Redis에 붙는다`() {
        val pong = connectionFactory.connection.use { it.ping() }

        assertThat(pong).isEqualTo("PONG")
    }

    @Test
    fun `기본 헬스 그룹은 Redis 지표를 빼고 realtime 그룹만 Redis 지표를 담는다`() {
        assertThat(healthGroups.primary.isMember("redis")).isFalse()
        assertThat(healthGroups.primary.isMember("db")).isTrue()
        assertThat(healthGroups.get("realtime")?.isMember("redis")).isTrue()
    }

    @Test
    fun `Redis 상태는 토큰 없이 actuator health realtime으로 따로 조회한다`() {
        val response = restTemplate.getForEntity("/actuator/health/realtime", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }
}
