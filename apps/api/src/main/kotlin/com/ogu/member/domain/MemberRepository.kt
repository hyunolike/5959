package com.ogu.member.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberRepository : JpaRepository<Member, Long> {
    /** 가입 방법과 관계없이 같은 이메일의 회원. 외부 계정끼리는 이메일이 겹칠 수 있어 여러 명일 수 있다. */
    fun findAllByEmail(email: String): List<Member>

    fun existsByNicknameKey(nicknameKey: String): Boolean

    /** 같은 회원의 온보딩 요청이 겹칠 때 직렬화하려고 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Member?
}
