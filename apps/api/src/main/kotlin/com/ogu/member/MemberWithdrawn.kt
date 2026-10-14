package com.ogu.member

import java.time.Instant

/**
 * 회원이 탈퇴했다(013 research R2). 회원 행을 비운 트랜잭션에서 나간다. 회원 번호와 때만 싣는다.
 *
 * - `post`가 같은 트랜잭션에서 받아 그 회원의 글과 댓글을 지운 것으로 바꾼다. 탈퇴와 "보이지 않는다"가 함께 커밋된다.
 * - 커밋 뒤에는 `post`가 본문을 비우고, `notification`이 받은 알림을, `report`가 주간 리포트를 지운다. 실패하면 다시 온다.
 */
data class MemberWithdrawn(
    val memberId: Long,
    val withdrawnAt: Instant,
)
