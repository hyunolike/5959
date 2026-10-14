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

    /** 탈퇴한 회원인가(013 research R5). 값만 읽으므로 영속성 컨텍스트에 있는 엔티티와 상관없이 DB의 지금 값이다. */
    @Query("select count(m) > 0 from Member m where m.id = :id and m.withdrawnAt is not null")
    fun isWithdrawn(
        @Param("id") id: Long,
    ): Boolean

    /**
     * 탈퇴하려고 회원 행을 `FOR UPDATE`로 잠근다(013 research R5). 그 회원의 글, 댓글, 공감의 INSERT는 FK 때문에 이 행에
     * `FOR KEY SHARE`를 잡으므로 여기서 줄을 선다. JPA의 쓰기 잠금은 `FOR NO KEY UPDATE`라 그것으로는 줄이 서지 않는다.
     * 없는 회원이면 null이다.
     */
    @Query(value = "select id from member where id = :id for update", nativeQuery = true)
    fun lockForWithdrawal(
        @Param("id") id: Long,
    ): Long?

    /** 같은 회원의 온보딩 요청이 겹칠 때 직렬화하려고 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Member?
}
