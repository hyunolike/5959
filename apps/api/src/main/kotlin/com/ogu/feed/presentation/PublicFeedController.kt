package com.ogu.feed.presentation

import com.ogu.feed.application.PublicFeedQuery
import com.ogu.feed.presentation.dto.PublicFeedResponse
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/**
 * 로그인 없이 부르는 조회(011). 이 경로 하나만 `SecurityPaths.PUBLIC_GET`에 있다. 로그인한 회원을 받지 않고, 쿼리 파라미터도
 * 받지 않는다(크기나 커서를 줘도 같은 목록이다).
 */
@Tag(name = "feed")
@RestController
class PublicFeedController(
    private val publicFeedQuery: PublicFeedQuery,
) {
    @Operation(
        operationId = "getPublicFeed",
        summary = "로그인하지 않은 방문자가 보는 최근 글 (011 US1, US2)",
        responses = [
            DocResponse(responseCode = "200", description = "최근 글 최대 20개. 넘겨 볼 수 없다"),
        ],
    )
    @GetMapping("/api/v1/public/feed")
    fun getPublicFeed(): ApiResponse<PublicFeedResponse> = ApiResponse.success(publicFeedQuery.get())
}
