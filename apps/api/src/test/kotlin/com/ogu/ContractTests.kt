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
 * constitution II(계약이 진실의 원천): apps/api가 구현한 API가 저장소 루트의 contracts/openapi.yaml과
 * 경로, 메서드, 응답 상태 코드 집합이 같은지 검증한다. 스키마(요청/응답 필드) 세부는 보지 않는다.
 *
 * 아직 구현하지 않은 오퍼레이션은 [pendingPaths]에 둔다(002-auth에서는 US1에서 가입, 내 프로필, 닉네임
 * 확인, 온보딩을, US2에서 로그인과 로그아웃을, US3에서 외부 계정 로그인을, US4에서 refresh를 뺐다 — 전부
 * 구현되어 지금은 비어 있다. 003-core-loop이 추가한 고민 글/피드/공감/댓글 13개 오퍼레이션도 각 스토리가
 * 컨트롤러를 추가할 때까지 여기 두었고 US4로 모두 빠졌다). 004-notification-mypage가 추가한 알림, 마이페이지,
 * 프로필 수정 11개 오퍼레이션도 각 스토리가 컨트롤러를 추가할 때까지 여기 둔다(US1에서 연결 표, 스트림, 안 읽은 수를,
 * US2에서 알림 목록, 하나 읽음, 모두 읽음을, US3에서 마이페이지 세 목록을, US4에서 감정 통계를, US5에서 프로필 수정을 빼서 지금은 비어 있다).
 * 실시간 스트림(`GET /api/v1/notifications/stream`)은 응답 형식(`text/event-stream`)까지만 계약으로 비교하고,
 * 그 안의 SSE 이벤트 형식(`notification`, `unread-count`, 하트비트)은 보지 않는다. 이벤트 형식은 SSE 통합 테스트가
 * 맡는다(004 research R15).
 * 각 스토리가 컨트롤러를 추가할 때마다 해당 오퍼레이션을 [pendingPaths]에서 뺀다.
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
        private const val CONTRACT_PATH = "../../contracts/openapi.yaml"

        /**
         * 아직 구현되지 않은 계약 오퍼레이션("METHOD path"). 스토리가 끝날 때마다 해당 오퍼레이션을 뺀다.
         * 003-core-loop이 계약에 더한 13개 중 아직 구현하지 않은 오퍼레이션이다(US1에서 createPost, getPostDetail을,
         * US2에서 getFeed를, US3에서 공감과 취소, 댓글 목록과 작성, 댓글 공감과 취소를,
         * US4에서 글과 댓글 수정, 삭제를 빼서 지금은 비어 있다).
         * 004-notification-mypage가 더한 11개도 알림(US1, US2), 마이페이지(US3, US4), 프로필 수정(US5)을
         * 구현한 스토리가 각자 뺐다. 005-safety가 더한 14개는 도움 리소스(US1), 신고(US3), 재검토 요청과
         * 운영자(US4)를 구현하는 스토리가 각자 뺀다.
         */
        val pendingPaths =
            setOf(
                "POST /api/v1/reports", // reportContent
                "POST /api/v1/review-requests", // requestReview
                "GET /api/v1/operator/assessments", // listAssessments
                "PUT /api/v1/operator/assessments/{assessmentId}/reviewed", // markAssessmentReviewed
                "GET /api/v1/operator/reports", // listReports
                "PUT /api/v1/operator/reports/{reportId}/decision", // decideReport
                "GET /api/v1/operator/review-requests", // listReviewRequests
                "PUT /api/v1/operator/review-requests/{reviewId}/decision", // decideReview
                "PUT /api/v1/operator/contents/{targetType}/{targetId}/hidden", // hideContent
                "DELETE /api/v1/operator/contents/{targetType}/{targetId}/hidden", // unhideContent
                "GET /api/v1/operator/terms", // listTerms
                "POST /api/v1/operator/terms", // addTerm
                "DELETE /api/v1/operator/terms/{termId}", // removeTerm
            )
    }
}
