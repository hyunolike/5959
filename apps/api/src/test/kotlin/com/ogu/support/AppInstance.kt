package com.ogu.support

import com.ogu.OguApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * `@SpringBootTest` 없이 직접 띄운 애플리케이션 하나(004 SC-003, Redis 장애). 서버 두 대를 같은 DB와 Redis에 붙이거나,
 * Redis가 없는 채로 기동해 보려고 쓴다. 가짜 감정 분석기([TestAiConfiguration])를 쓰고, 테스트 소스의 다른 설정
 * (`@TestConfiguration`, 테스트용 컴포넌트)은 컴포넌트 검색에서 뺀다. `@SpringBootTest`라면 Spring Boot가 해 주는 일이다.
 */
class AppInstance private constructor(
    val context: ConfigurableApplicationContext,
) : AutoCloseable {
    val port: Int = (context as WebServerApplicationContext).webServer!!.port

    val mockMvc: MockMvc =
        MockMvcBuilders
            .webAppContextSetup(context as WebApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    inline fun <reified T : Any> bean(): T = context.getBean(T::class.java)

    override fun close() {
        context.close()
    }

    companion object {
        private val DEFAULTS: Map<String, String> = mapOf("server.port" to "0", "spring.main.banner-mode" to "off")

        fun start(properties: Map<String, String>): AppInstance {
            val context =
                SpringApplicationBuilder(OguApplication::class.java, TestAiConfiguration::class.java)
                    .initializers(
                        ApplicationContextInitializer<ConfigurableApplicationContext> {
                            it.beanFactory.registerSingleton("excludeTestSources", ExcludeTestSources())
                        },
                    )
                    // 명령행 인자로 넘겨 application.yml의 값(예: `REDIS_URL` 기본값)보다 앞서게 한다
                    .run(*(DEFAULTS + properties).map { (key, value) -> "--$key=$value" }.toTypedArray())
            return AppInstance(context)
        }
    }
}

/** 테스트 소스에서 컴파일된 클래스와 `@TestConfiguration`을 컴포넌트 검색에서 뺀다. */
private class ExcludeTestSources : TypeExcludeFilter() {
    override fun match(
        metadataReader: MetadataReader,
        metadataReaderFactory: MetadataReaderFactory,
    ): Boolean =
        metadataReader.annotationMetadata.hasAnnotation(TestConfiguration::class.java.name) ||
            metadataReader.resource.uri
                .toString()
                .contains(TEST_CLASSES)

    override fun equals(other: Any?): Boolean = other is ExcludeTestSources

    override fun hashCode(): Int = javaClass.hashCode()

    private companion object {
        const val TEST_CLASSES = "/classes/kotlin/test/"
    }
}
