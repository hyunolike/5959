package com.ogu.feed.presentation

import com.ogu.feed.application.EmotionStatsQuery
import com.ogu.feed.application.MyPageQuery
import com.ogu.feed.presentation.dto.EmotionStatsResponse
import com.ogu.feed.presentation.dto.FeedPageResponse
import com.ogu.member.AuthenticatedMember
import com.ogu.post.MyCommentPage
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/**
 * 마이페이지 조회(004 US3, US4). 글, 몬스터, 감정을 모아야 해서 feed 모듈에 둔다. 온보딩 전 회원은 `OnboardingGuard`가
 * 403 ONBOARDING_REQUIRED로 막는다.
 */
@Tag(name = "mypage")
@RestController
@SecurityRequirement(name = "bearer")
class MyPageController(
    private val myPageQuery: MyPageQuery,
    private val emotionStatsQuery: EmotionStatsQuery,
) {
    @Operation(
        operationId = "getMyPosts",
        summary = "내가 쓴 글 (US3-AC1, AC4). 최신순, 지운 글 제외",
        responses = [
            DocResponse(responseCode = "200", description = "글 페이지. 항목은 피드와 같다"),
            DocResponse(responseCode = "400", description = "size가 1~50 밖이거나 커서가 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/members/me/posts")
    fun getMyPosts(
        member: AuthenticatedMember,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<FeedPageResponse> = ApiResponse.success(myPageQuery.posts(member.memberId, cursor, size))

    @Operation(
        operationId = "getMyComments",
        summary = "내 댓글과 답글 (US3-AC2, AC4). 최신순, 지운 댓글과 지운 글의 댓글 제외",
        responses = [
            DocResponse(responseCode = "200", description = "댓글 페이지"),
            DocResponse(responseCode = "400", description = "size가 1~50 밖이거나 커서가 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/members/me/comments")
    fun getMyComments(
        member: AuthenticatedMember,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<MyCommentPage> = ApiResponse.success(myPageQuery.comments(member.memberId, cursor, size))

    @Operation(
        operationId = "getMyLikedPosts",
        summary = "공감한 글 (US3-AC3, AC4). 공감한 시각의 최신순, 취소한 공감과 지운 글 제외",
        responses = [
            DocResponse(responseCode = "200", description = "글 페이지. 항목은 피드와 같다"),
            DocResponse(responseCode = "400", description = "size가 1~50 밖이거나 커서가 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/members/me/liked-posts")
    fun getMyLikedPosts(
        member: AuthenticatedMember,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<FeedPageResponse> = ApiResponse.success(myPageQuery.likedPosts(member.memberId, cursor, size))

    @Operation(
        operationId = "getMyEmotionStats",
        summary = "감정 통계 (US4-AC1~AC5)",
        responses = [
            DocResponse(responseCode = "200", description = "감정 통계"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/members/me/emotion-stats")
    fun getMyEmotionStats(member: AuthenticatedMember): ApiResponse<EmotionStatsResponse> =
        ApiResponse.success(emotionStatsQuery.get(member.memberId))
}
