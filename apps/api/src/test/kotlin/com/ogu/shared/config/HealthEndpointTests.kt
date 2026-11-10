package com.ogu.shared.config

import com.ogu.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 018 T008(research R2): `/actuator/health/liveness`는 DB를 보지 않는다. Render 헬스 체크와 깨우기 요청이 10분마다 이
 * 주소를 부르므로, 여기서 DB에 닿으면 Neon이 잠들지 못한다. `/actuator/health`는 지금처럼 DB를 본다.
 *
 * 요청을 처리하는 Tomcat 스레드가 DB 연결을 몇 번 꺼냈는지 센다. 주기 작업 같은 다른 스레드의 연결은 세지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class, HealthEndpointTests.CountingDataSource::class)
class HealthEndpointTests {
    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @BeforeEach
    fun reset() = REQUEST_CONNECTIONS.set(0)

    @Test
    fun `US2-AC3 liveness는 DB 연결을 꺼내지 않고 UP이다`() {
        val response = restTemplate.getForEntity("/actuator/health/liveness", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
        assertThat(REQUEST_CONNECTIONS.get()).isZero()
    }

    @Test
    fun `US2-AC5 liveness는 로그인 없이 부른다`() {
        val response = restTemplate.getForEntity("/actuator/health/liveness", String::class.java)

        assertThat(response.statusCode).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `actuator health는 DB를 본다`() {
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(REQUEST_CONNECTIONS.get()).isPositive()
    }

    // Spring이 @Import로 이 클래스를 만들어야 해서 object로 둘 수 없다.
    @Suppress("UtilityClassWithPublicConstructor")
    @TestConfiguration(proxyBeanMethods = false)
    class CountingDataSource {
        companion object {
            @Bean
            @JvmStatic
            fun countingDataSourcePostProcessor(): BeanPostProcessor =
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(
                        bean: Any,
                        beanName: String,
                    ): Any = if (bean is DataSource) RequestThreadCounting(bean) else bean
                }
        }
    }

    private class RequestThreadCounting(
        target: DataSource,
    ) : DelegatingDataSource(target) {
        override fun getConnection(): Connection {
            count()
            return super.getConnection()
        }

        override fun getConnection(
            username: String,
            password: String,
        ): Connection {
            count()
            return super.getConnection(username, password)
        }

        private fun count() {
            if (Thread.currentThread().name.startsWith("http-nio-")) REQUEST_CONNECTIONS.incrementAndGet()
        }
    }

    companion object {
        private val REQUEST_CONNECTIONS = AtomicInteger()
    }
}
