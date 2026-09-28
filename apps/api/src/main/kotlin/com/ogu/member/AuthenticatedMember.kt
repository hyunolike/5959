package com.ogu.member

import java.util.UUID

/**
 * 컨트롤러가 인증된 요청자를 받을 때 쓰는 타입. `HandlerMethodArgumentResolver`가 JWT 클레임에서 채운다.
 */
data class AuthenticatedMember(
    val memberId: Long,
    val sessionId: UUID,
    val onboarded: Boolean,
)
