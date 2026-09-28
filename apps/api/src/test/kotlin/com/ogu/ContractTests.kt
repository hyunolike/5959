package com.ogu

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * constitution II(계약이 진실의 원천): apps/api가 구현한 API가 specs/002-auth/contracts/openapi.yaml과
 * 경로, 메서드, 응답 상태 코드 집합이 같은지 검증한다. 스키마(요청/응답 필드) 세부는 보지 않는다.
 *
 * 지금은 어떤 컨트롤러도 없으므로 [pendingPaths]가 계약의 8개 오퍼레이션 전부를 담고 있어 통과한다.
 * 각 스토리가 컨트롤러를 추가할 때마다 해당 오퍼레이션을 [pendingPaths]에서 뺀다. T066에서 빈 집합이 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class ContractTests {
    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `구현된 API가 계약과 경로, 메서드, 응답 상태 코드 집합이 같다`() {
        val expected = extractOperations(loadContract())
        val actual = extractOperations(loadActualSpec())

        val failures = ContractComparison.compare(expected, actual, pendingPaths)

        assertThat(failures).isEmpty()
    }

    private fun loadContract(): OpenAPI {
        val openApi = OpenAPIV3Parser().read(CONTRACT_PATH)
        return requireNotNull(openApi) { "계약 파일을 읽지 못했습니다: $CONTRACT_PATH" }
    }

    private fun loadActualSpec(): OpenAPI {
        val json = restTemplate.getForObject("/v3/api-docs", String::class.java)
        val result = OpenAPIV3Parser().readContents(json)
        return requireNotNull(result.openAPI) { "/v3/api-docs 파싱에 실패했습니다: ${result.messages}" }
    }

    private fun extractOperations(openApi: OpenAPI): Map<String, Set<String>> {
        val paths = openApi.paths ?: return emptyMap()
        return paths.entries
            .filter { (path, _) -> path.startsWith("/api/v1") }
            .flatMap { (path, item) ->
                item.readOperationsMap().entries.map { (method, operation) ->
                    val key = "${method.name} $path"
                    val statusCodes = operation.responses?.keys?.toSet() ?: emptySet()
                    key to statusCodes
                }
            }.toMap()
    }

    companion object {
        private const val CONTRACT_PATH = "../../specs/002-auth/contracts/openapi.yaml"

        /**
         * 아직 구현되지 않은 계약 오퍼레이션("METHOD path"). 스토리가 끝날 때마다 해당 오퍼레이션을 뺀다.
         */
        val pendingPaths =
            setOf(
                "POST /api/v1/auth/signup",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/oauth/{provider}",
                "POST /api/v1/auth/refresh",
                "POST /api/v1/auth/logout",
                "GET /api/v1/members/me",
                "GET /api/v1/members/nickname-availability",
                "PUT /api/v1/members/me/onboarding",
            )
    }
}
