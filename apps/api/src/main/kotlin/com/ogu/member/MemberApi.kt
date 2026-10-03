package com.ogu.member

/**
 * 회원 모듈이 다른 모듈에 노출하는 파사드. 다른 모듈은 이 인터페이스를 거쳐서만 회원 정보를 읽는다.
 */
interface MemberApi {
    fun getMember(memberId: Long): MemberInfo

    /** 여러 회원을 쿼리 한 번으로 읽는다. 없는 ID는 결과에서 빠진다. */
    fun getMembers(ids: Collection<Long>): Map<Long, MemberInfo>
}
