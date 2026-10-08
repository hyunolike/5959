package com.ogu.post.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.post.application.CommentService
import com.ogu.post.presentation.dto.CommentPageResponse
import com.ogu.post.presentation.dto.CommentResponse
import com.ogu.post.presentation.dto.CommentUpdateRequest
import com.ogu.post.presentation.dto.CommentWriteRequest
import com.ogu.post.presentation.dto.masked
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import com.ogu.shared.text.ContentMask
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 댓글 목록, 작성, 수정, 삭제. 첫 댓글의 HP 반영은 같은 트랜잭션에서 monster 모듈이 한다(research R5). */
@Tag(name = "reaction")
@RestController
@SecurityRequirement(name = "bearer")
class CommentController(
    private val commentService: CommentService,
    private val contentMask: ContentMask,
) {
    @Operation(
        operationId = "getComments",
        summary = "댓글 목록. 원 댓글 50개씩, 답글은 원 댓글 아래에 모두",
        responses = [
            DocResponse(responseCode = "200", description = "댓글 페이지"),
            DocResponse(responseCode = "400", description = "글 ID나 커서 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 글 (POST_NOT_FOUND)"),
        ],
    )
    @GetMapping("/api/v1/posts/{postId}/comments")
    fun getComments(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
        @RequestParam(required = false) cursor: String?,
    ): ApiResponse<CommentPageResponse> {
        val page = commentService.list(postId, member.memberId, cursor)
        return ApiResponse.success(page.copy(items = page.items.map { it.masked(contentMask) }))
    }

    @Operation(
        operationId = "createComment",
        summary = "댓글 또는 답글 작성 (US3-AC2, AC3)",
        responses = [
            DocResponse(responseCode = "201", description = "작성됨"),
            DocResponse(
                responseCode = "400",
                description = "본문이 1~300자가 아님 (INVALID_REQUEST) 또는 답글에 답글 (INVALID_PARENT_COMMENT)",
            ),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(
                responseCode = "404",
                description = "없거나 삭제된 글 (POST_NOT_FOUND), 없거나 삭제된 원 댓글 (COMMENT_NOT_FOUND)",
            ),
        ],
    )
    @PostMapping("/api/v1/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createComment(
        member: AuthenticatedMember,
        @PathVariable postId: Long,
        @RequestBody request: CommentWriteRequest,
    ): ApiResponse<CommentResponse> {
        val content = request.content ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "댓글을 입력해 주세요.")
        return ApiResponse.success(commentService.write(postId, member.memberId, content, request.parentId))
    }

    @Operation(
        operationId = "updateComment",
        summary = "댓글 수정 (US4-AC3)",
        responses = [
            DocResponse(responseCode = "204", description = "수정됨"),
            DocResponse(responseCode = "400", description = "본문이 1~300자가 아니거나 ID 형식 오류 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED) 또는 남의 댓글 (NOT_AUTHOR)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 댓글, 삭제된 글의 댓글 (COMMENT_NOT_FOUND)"),
        ],
    )
    @PatchMapping("/api/v1/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateComment(
        member: AuthenticatedMember,
        @PathVariable commentId: Long,
        @RequestBody request: CommentUpdateRequest,
    ) {
        val content = request.content ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "댓글을 입력해 주세요.")
        commentService.update(commentId, member.memberId, content)
    }

    @Operation(
        operationId = "deleteComment",
        summary = "댓글 삭제 (US4-AC3). 원 댓글이면 답글도 함께 삭제",
        responses = [
            DocResponse(responseCode = "204", description = "삭제됨"),
            DocResponse(responseCode = "400", description = "ID 형식 오류 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED) 또는 남의 댓글 (NOT_AUTHOR)"),
            DocResponse(responseCode = "404", description = "없거나 삭제된 댓글, 삭제된 글의 댓글 (COMMENT_NOT_FOUND)"),
        ],
    )
    @DeleteMapping("/api/v1/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(
        member: AuthenticatedMember,
        @PathVariable commentId: Long,
    ) {
        commentService.delete(commentId, member.memberId)
    }
}
