package com.ogu.member

import java.time.Instant

/**
 * 비밀번호 재설정 요청을 받았다(015 research R3). 요청을 받은 트랜잭션에서 나가고 `member`가 커밋 뒤에 받아 링크를 만들어
 * 메일을 보낸다.
 *
 * [memberId]가 null이면 보낼 회원이 없는 요청이다(가입하지 않은 이메일, 소셜 가입, 한도를 넘은 이메일). 받는 쪽은 아무것도
 * 하지 않는다. 그런 요청에도 이벤트를 내는 까닭은 가입한 이메일과 하는 일의 양을 같게 해, 걸리는 시간으로 가입 여부가
 * 드러나지 않게 하려는 것이다(FR-002).
 *
 * 이메일과 링크의 값을 싣지 않는다. 발행 기록에 그대로 남는다(FR-010).
 */
data class PasswordResetRequested(
    val memberId: Long?,
    val requestedAt: Instant,
)
