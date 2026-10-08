package com.ogu.post.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.post.application.PostService
import com.ogu.post.presentation.dto.PostCreatedResponse
import com.ogu.post.presentation.dto.PostUpdateRequest
import com.ogu.post.presentation.dto.PostWriteRequest
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

@Tag(name = "post")
@RestController
@RequestMapping("/api/v1/posts")
@SecurityRequirement(name = "bearer")
class PostController(
    private val postService: PostService,
) {
    @Operation(
        operationId = "createPost",
        summary = "고민 글 작성 (US1-AC1, AC2). 감정 분석은 비동기",
        responses = [
            DocResponse(responseCode = "201", description = "저장됨. analysisStatus는 PENDING"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "429", description = "1시간에 10개 초과 (POST_RATE_LIMITED, research R9)"),
        ],
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        member: AuthenticatedMember,
        @RequestBody request: PostWriteRequest,
    ): ApiResponse<PostCreatedResponse> {
        val content = request.content ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "본문을 입력해 주세요.")
        val commentTone =
            request.commentTone ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "댓글 말투를 골라 주세요.")
        val postId = postService.create(member.memberId, content, commentTone)
        return ApiResponse.success(PostCreatedResponse(postId))
    }

    @Operation(
        operationId = "updatePost",
        summary = "글 수정 (US4-AC1). 몬스터는 바뀌지 않는다",
        responses = [
            DocResponse(responseCode = "204", description = "수정됨"),
            DocResponse(responseCode = "400", description = "입력 검증 실패나 ID 형식 오류 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED) 또는 남의 글 (NOT_AUTHOR)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 글 (POST_NOT_FOUND)"),
        ],
    )
    @PatchMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun update(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
        @RequestBody request: PostUpdateRequest,
    ) {
        postService.update(postId, member.memberId, request.content, request.commentTone)
    }

    @Operation(
        operationId = "deletePost",
        summary = "글 삭제 (US4-AC2)",
        responses = [
            DocResponse(responseCode = "204", description = "삭제됨"),
            DocResponse(responseCode = "400", description = "ID 형식 오류 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED) 또는 남의 글 (NOT_AUTHOR)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 글 (POST_NOT_FOUND)"),
        ],
    )
    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
    ) {
        postService.delete(postId, member.memberId)
    }
}
