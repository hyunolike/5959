package com.ogu.feed.presentation

import com.ogu.feed.application.PostDetailQuery
import com.ogu.feed.presentation.dto.PostDetailResponse
import com.ogu.member.AuthenticatedMember
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 피드와 글 상세 조회. 글 쓰기와 수정은 post 모듈의 PostController가 맡는다. */
@Tag(name = "feed")
@RestController
@SecurityRequirement(name = "bearer")
class FeedController(
    private val postDetailQuery: PostDetailQuery,
) {
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
}
