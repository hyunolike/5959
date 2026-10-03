package com.ogu

import com.ogu.support.QueryCountConfiguration
import com.ogu.support.TestAiConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트용 Postgres. 감정 분석은 실제 LLM 대신 가짜 분석기를 쓴다([TestAiConfiguration]).
 * JDBC 문장 수는 [QueryCountConfiguration]이 센다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestAiConfiguration::class, QueryCountConfiguration::class)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"),
        )
}
