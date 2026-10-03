package com.ogu.post.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.post.application.LikeService
import com.ogu.post.presentation.dto.LikeResultResponse
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 글 공감과 댓글 공감. HP 반영은 같은 트랜잭션에서 monster 모듈이 한다(research R5). */
@Tag(name = "reaction")
@RestController
@SecurityRequirement(name = "bearer")
class LikeController(
    private val likeService: LikeService,
) {
    @Operation(
        operationId = "likePost",
        summary = "글 공감 (US3-AC1, US3-AC8)",
        responses = [
            DocResponse(responseCode = "200", description = "공감됨"),
            DocResponse(responseCode = "400", description = "글 ID 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 또는 자기 글 (CANNOT_LIKE_OWN_POST)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 글 (POST_NOT_FOUND)"),
            DocResponse(responseCode = "409", description = "이미 공감함 (ALREADY_LIKED)"),
        ],
    )
    @PostMapping("/api/v1/posts/{postId}/likes")
    fun likePost(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
    ): ApiResponse<LikeResultResponse> = ApiResponse.success(likeService.likePost(postId, member.memberId))

    @Operation(
        operationId = "unlikePost",
        summary = "글 공감 취소 (US3-AC6). HP는 돌아오지 않는다",
        responses = [
            DocResponse(responseCode = "200", description = "취소됨. 공감하지 않은 상태여도 200"),
            DocResponse(responseCode = "400", description = "글 ID 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 글 (POST_NOT_FOUND)"),
        ],
    )
    @DeleteMapping("/api/v1/posts/{postId}/likes/me")
    fun unlikePost(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
    ): ApiResponse<LikeResultResponse> = ApiResponse.success(likeService.unlikePost(postId, member.memberId))

    @Operation(
        operationId = "likeComment",
        summary = "댓글 공감 (US3-AC4)",
        responses = [
            DocResponse(responseCode = "200", description = "공감됨"),
            DocResponse(responseCode = "400", description = "댓글 ID 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 댓글 (COMMENT_NOT_FOUND)"),
            DocResponse(responseCode = "409", description = "이미 공감함 (ALREADY_LIKED)"),
        ],
    )
    @PostMapping("/api/v1/comments/{commentId}/likes")
    fun likeComment(
        member: AuthenticatedMember,
        @PathVariable commentId: Long,
    ): ApiResponse<LikeResultResponse> = ApiResponse.success(likeService.likeComment(commentId, member.memberId))

    @Operation(
        operationId = "unlikeComment",
        summary = "댓글 공감 취소 (US3-AC6)",
        responses = [
            DocResponse(responseCode = "200", description = "취소됨"),
            DocResponse(responseCode = "400", description = "댓글 ID 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 댓글 (COMMENT_NOT_FOUND)"),
        ],
    )
    @DeleteMapping("/api/v1/comments/{commentId}/likes/me")
    fun unlikeComment(
        member: AuthenticatedMember,
        @PathVariable commentId: Long,
    ): ApiResponse<LikeResultResponse> = ApiResponse.success(likeService.unlikeComment(commentId, member.memberId))
}
