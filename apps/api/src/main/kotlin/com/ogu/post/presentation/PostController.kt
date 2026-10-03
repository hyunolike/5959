package com.ogu.post.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.post.application.PostService
import com.ogu.post.presentation.dto.PostCreatedResponse
import com.ogu.post.presentation.dto.PostWriteRequest
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
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
}
