package com.ogu.member

import java.util.UUID

/**
 * 회원 모듈이 다른 모듈에 노출하는 파사드. 다른 모듈은 이 인터페이스를 거쳐서만 회원 정보를 읽는다.
 */
interface MemberApi {
    fun getMember(memberId: Long): MemberInfo

    /** 여러 회원을 쿼리 한 번으로 읽는다. 없는 ID는 결과에서 빠진다. */
    fun getMembers(ids: Collection<Long>): Map<Long, MemberInfo>

    /** 실시간 알림 스트림용 일회용 연결 표를 발급한다(004 research R3). 30초 안에 한 번만 쓸 수 있다. */
    fun issueStreamTicket(
        memberId: Long,
        sessionId: UUID,
    ): StreamTicket

    /** 연결 표를 소비한다. 성공하면 회원 ID, 없거나 이미 썼거나 만료됐거나 발급한 세션이 끝났으면 null이다. */
    fun consumeStreamTicket(ticket: String): Long?
}
