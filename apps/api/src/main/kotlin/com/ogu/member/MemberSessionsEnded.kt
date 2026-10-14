package com.ogu.member

/**
 * 회원의 모든 로그인 상태를 끝냈다(015 FR-006). 비밀번호를 재설정한 트랜잭션에서 나간다. 회원 번호만 싣는다.
 *
 * 세션 행을 지우면 새 요청과 새 실시간 연결은 막히지만 이미 열린 알림 스트림은 수명(15분)까지 남는다. `notification`이
 * 커밋 뒤에 받아 그 연결을 닫는다. 탈퇴는 [MemberWithdrawn]으로 같은 일을 하므로 이 이벤트를 내지 않는다.
 */
data class MemberSessionsEnded(
    val memberId: Long,
)
