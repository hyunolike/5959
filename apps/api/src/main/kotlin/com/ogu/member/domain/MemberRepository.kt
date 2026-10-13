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

    /**
     * AI 친구의 닉네임에서 띄어쓰기를 뺀 것이 [key]와 같은가(009 US2-AC5). AI 친구의 닉네임에는 띄어쓰기가 있어
     * 사람의 닉네임과 유일 제약으로는 겹치지 않는다.
     */
    @Query(
        value = "select exists(select 1 from member where kind = 'AI' and replace(nickname_key, ' ', '') = :key)",
        nativeQuery = true,
    )
    fun existsAiNicknameKeyWithoutSpaces(
        @Param("key") key: String,
    ): Boolean

    fun existsByIdAndRole(
        id: Long,
        role: MemberRole,
    ): Boolean

    /** 같은 회원의 온보딩 요청이 겹칠 때 직렬화하려고 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Member?
}
