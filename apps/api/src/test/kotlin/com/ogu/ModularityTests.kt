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
    fun `notification 모듈은 emotion 모듈을 모른다`() {
        val notification = modules.getModuleByName("notification").getOrNull()
        assertThat(notification).isNotNull()

        val dependencies =
            notification!!
                .getDirectDependencies(modules)
                .uniqueModules()
                .map { it.identifier.toString() }
                .toList()
        assertThat(dependencies).doesNotContain("emotion")
    }

    @Test
    fun `모듈 구조 문서를 생성한다`() {
        // build/spring-modulith-docs 아래에 C4/PlantUML 다이어그램과 모듈 캔버스 생성
        Documenter(modules).writeDocumentation()
    }

    companion object {
        private const val SHARED = "shared"

        /**
         * 모듈별로 의존해도 되는 다른 모듈(plan.md Constitution Check, overview 5.1, 004 data-model.md "모듈 의존 그래프").
         * `shared`는 모두가 쓸 수 있다.
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
                // 006: 마이페이지 통계에 함께 물리친 보스 수를 더한다
                // 007: 글 상세의 추천을 recommend가 고른 ID로 조립한다
                "feed" to setOf("post", "monster", "emotion", "member", "raid", "recommend"),
                // 004: 행동한 회원 닉네임과 연결 표 때문에 member를 더한다. 몬스터 생성은 MonsterSpawned로 받아 emotion을 모른다
                // 005: 위험 감지 결과를 safety의 이벤트로 받는다
                // 006: 보스 처치를 raid의 이벤트로 받는다
                "notification" to setOf("post", "monster", "member", "safety", "raid"),
                // 005: post의 이벤트를 받아 판정하고 PostModerationApi로 숨긴다. post는 safety를 모른다
                "safety" to setOf("post", "ai", "member"),
                // 006: 보스의 감정을 post와 emotion으로 정한다. member는 컨트롤러가 받는 인증된 회원 타입 때문이다.
                // monster와는 서로 모른다(글의 몬스터와 보스는 다른 것이다)
                "raid" to setOf("post", "emotion", "member"),
                // 007: 글의 임베딩으로 가까운 글을 찾는다. 임베딩이 없으면 emotion의 결과로 대신한다. feed가 카드로 조립한다
                "recommend" to setOf("post", "ai", "emotion"),
            )
    }
}
