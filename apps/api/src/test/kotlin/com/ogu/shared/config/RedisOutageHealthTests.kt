package com.ogu.shared.config

import com.ogu.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus

/**
 * 004 Batch 1(T004, research R5): Redis가 내려가 있어도 API는 뜨고 `/actuator/health`는 UP이다.
 * 배포 스크립트가 이 응답으로 롤백을 정하므로 Redis 장애가 롤백으로 이어지면 안 된다.
 * Redis 주소를 아무것도 듣지 않는 포트로 바꿔 끼워 장애를 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class, RedisOutageHealthTests.UnreachableRedis::class)
class RedisOutageHealthTests {
    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `Redis가 내려가 있어도 기본 헬스 체크는 UP이다`() {
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun `Redis가 내려가 있으면 realtime 헬스 그룹은 DOWN이다`() {
        val response = restTemplate.getForEntity("/actuator/health/realtime", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body).contains("\"status\":\"DOWN\"")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class UnreachableRedis {
        @Bean
        @Primary
        fun unreachableRedisConnectionDetails(): DataRedisConnectionDetails =
            object : DataRedisConnectionDetails {
                override fun getStandalone(): DataRedisConnectionDetails.Standalone =
                    DataRedisConnectionDetails.Standalone.of("127.0.0.1", UNREACHABLE_PORT)
            }
    }

    companion object {
        /** 아무 서비스도 듣지 않는 포트(TCP 1). 연결이 바로 거절된다. */
        private const val UNREACHABLE_PORT = 1
    }
}
