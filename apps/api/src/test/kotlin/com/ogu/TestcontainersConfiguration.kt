package com.ogu

import com.ogu.support.MonsterDefeatedRecorder
import com.ogu.support.QueryCountConfiguration
import com.ogu.support.TestAiConfiguration
import com.redis.testcontainers.RedisContainer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트용 Postgres와 Redis(004 실시간 알림의 인스턴스 간 신호, research R5). 감정 분석은 실제 LLM 대신 가짜 분석기를 쓴다([TestAiConfiguration]).
 * JDBC 문장 수는 [QueryCountConfiguration]이 세고, 몬스터 처치 이벤트는 [MonsterDefeatedRecorder]가 모은다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestAiConfiguration::class, QueryCountConfiguration::class, MonsterDefeatedRecorder::class)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"),
        )

    @Bean
    @ServiceConnection
    fun redisContainer(): RedisContainer = RedisContainer(DockerImageName.parse("redis:7.4-alpine"))
}
