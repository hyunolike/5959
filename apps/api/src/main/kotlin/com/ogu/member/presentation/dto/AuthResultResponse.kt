package com.ogu.member.presentation.dto

import com.ogu.member.application.IssuedTokens
import com.ogu.member.domain.Member
import java.time.Instant

/** 계약의 `AuthResult`. 토큰은 BFF가 쿠키로 옮기고 브라우저에는 `member`만 준다. */
data class AuthResultResponse(
    val member: MemberProfileResponse,
    val tokens: AuthTokensResponse,
    val newMember: Boolean,
) {
    companion object {
        fun of(
            member: Member,
            tokens: IssuedTokens,
            newMember: Boolean,
        ) = AuthResultResponse(
            member = MemberProfileResponse.from(member),
            tokens = AuthTokensResponse.from(tokens),
            newMember = newMember,
        )
    }
}

/** 계약의 `AuthTokens`. */
data class AuthTokensResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String?,
    val refreshTokenExpiresAt: Instant,
) {
    companion object {
        fun from(tokens: IssuedTokens) =
            AuthTokensResponse(
                accessToken = tokens.accessToken,
                accessTokenExpiresAt = tokens.accessTokenExpiresAt,
                refreshToken = tokens.refreshToken,
                refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
            )
    }
}
