package com.ogu.member.application

import java.time.Instant
import java.util.UUID

/**
 * 세션 발급과 갱신 결과. 계약의 `AuthTokens`에 세션 ID를 더한 것이다.
 * [refreshToken]은 refresh 유예 구간(교체 후 30초)에서 null이다(research R2).
 */
data class IssuedTokens(
    val sessionId: UUID,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String?,
    val refreshTokenExpiresAt: Instant,
)
