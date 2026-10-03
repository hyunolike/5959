package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import kotlin.jvm.optionals.getOrNull

class ModularityTests {
    private val modules = ApplicationModules.of(OguApplication::class.java)

    @Test
    fun `모듈 경계와 의존 규칙을 검증한다`() {
        modules.verify()
    }

    @Test
    fun `모듈 목록은 overview 5_1 그래프와 같다`() {
        val names = modules.stream().map { it.identifier.toString() }.toList()

        assertThat(names).containsExactlyInAnyOrderElementsOf(ALLOWED_DEPENDENCIES.keys)
    }

    @Test
    fun `각 모듈은 허용된 방향으로만 다른 모듈에 의존한다`() {
        ALLOWED_DEPENDENCIES.forEach { (name, allowed) ->
            val module = modules.getModuleByName(name).getOrNull()
            assertThat(module).describedAs("모듈 %s", name).isNotNull()

            val actual =
                module!!
                    .getDirectDependencies(modules)
                    .uniqueModules()
                    .map { it.identifier.toString() }
                    .toList()
            assertThat(actual)
                .describedAs("%s 모듈의 의존(허용: %s)", name, allowed)
                .isSubsetOf(allowed + SHARED)
        }
    }

    @Test
    fun `모듈 구조 문서를 생성한다`() {
        // build/spring-modulith-docs 아래에 C4/PlantUML 다이어그램과 모듈 캔버스 생성
        Documenter(modules).writeDocumentation()
    }

    companion object {
        private const val SHARED = "shared"

        /**
         * 모듈별로 의존해도 되는 다른 모듈(plan.md Constitution Check, overview 5.1). `shared`는 모두가 쓸 수 있다.
         * 이벤트 구독도 발행 모듈의 이벤트 타입에 의존하므로 같은 방향이어야 한다.
         */
        private val ALLOWED_DEPENDENCIES =
            mapOf(
                SHARED to emptySet(),
                "member" to emptySet(),
                "post" to setOf("member"),
                "ai" to emptySet(),
                "emotion" to setOf("post", "ai"),
                "monster" to setOf("post", "emotion"),
                "feed" to setOf("post", "monster", "emotion", "member"),
            )
    }
}
