package com.ogu.feed.presentation

import com.ogu.feed.application.FeedQuery
import com.ogu.feed.application.PostDetailQuery
import com.ogu.feed.application.SimilarPostsQuery
import com.ogu.feed.application.SimilarPostsResponse
import com.ogu.feed.presentation.dto.FeedPageResponse
import com.ogu.feed.presentation.dto.PostDetailResponse
import com.ogu.member.AuthenticatedMember
import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.post.PostOrder
import com.ogu.post.PostPageQuery
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 피드와 글 상세 조회. 글 쓰기와 수정은 post 모듈의 PostController가 맡는다. */
@Tag(name = "feed")
@RestController
@SecurityRequirement(name = "bearer")
class FeedController(
    private val postDetailQuery: PostDetailQuery,
    private val feedQuery: FeedQuery,
    private val similarPostsQuery: SimilarPostsQuery,
) {
    @Operation(
        operationId = "getFeed",
        summary = "피드 (US2)",
        responses = [
            DocResponse(responseCode = "200", description = "피드 페이지"),
            DocResponse(
                responseCode = "400",
                description = "size가 1~50 밖이거나, 커서, 정렬, 직군, 경력 값이 틀림 (INVALID_REQUEST)",
            ),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    // 쿼리 파라미터를 계약(getFeed)과 한 줄씩 맞춰 둔다. 묶음 객체로 바꾸면 springdoc 문서와 오류 매핑이 달라진다.
    @Suppress("LongParameterList")
    @GetMapping("/api/v1/feed")
    fun getFeed(
        member: AuthenticatedMember,
        @RequestParam(defaultValue = "LATEST") order: PostOrder,
        @Parameter(description = "여러 번 줄 수 있다. 하나라도 맞으면 통과")
        @RequestParam(name = "jobRole", required = false) jobRoles: List<JobRole?>?,
        @RequestParam(name = "careerYear", required = false) careerYears: List<CareerYear?>?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<FeedPageResponse> =
        ApiResponse.success(
            feedQuery.get(
                PostPageQuery(
                    viewerId = member.memberId,
                    order = order,
                    jobRoles = jobRoles.requireNoBlank(),
                    careerYears = careerYears.requireNoBlank(),
                    cursor = cursor,
                    size = size,
                ),
            ),
        )

    @Operation(
        operationId = "getPostDetail",
        summary = "글 상세 (FR-012, FR-015). 분석 중이면 monster는 null",
        responses = [
            DocResponse(responseCode = "200", description = "글 상세"),
            DocResponse(responseCode = "400", description = "글 ID 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 글 (POST_NOT_FOUND)"),
        ],
    )
    @GetMapping("/api/v1/posts/{postId}")
    fun getPostDetail(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
    ): ApiResponse<PostDetailResponse> = ApiResponse.success(postDetailQuery.get(postId, member.memberId))

    @Operation(
        operationId = "getSimilarPosts",
        summary = "이 글과 비슷한 고민 (007 US1-AC1, US2-AC2). 가까운 순서로 최대 5개",
        responses = [
            DocResponse(responseCode = "200", description = "추천. 보여 줄 글이 없으면 basis가 NONE이다"),
            DocResponse(responseCode = "400", description = "글 ID 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "없거나, 지웠거나, 숨긴 글 (POST_NOT_FOUND)"),
        ],
    )
    @GetMapping("/api/v1/posts/{postId}/similar")
    fun getSimilarPosts(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
    ): ApiResponse<SimilarPostsResponse> = ApiResponse.success(similarPostsQuery.get(postId, member.memberId))
}

/**
 * `?jobRole=&jobRole=HR`나 `?jobRole=,HR`처럼 빈 값이 섞이면 Spring이 null 원소로 묶는다. 모르는 이름(ASTRONAUT)과
 * 똑같이 400 INVALID_REQUEST로 거절한다.
 */
private fun <T : Any> List<T?>?.requireNoBlank(): Set<T> =
    orEmpty()
        .map { it ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "직군과 경력에 빈 값을 줄 수 없습니다.") }
        .toSet()
