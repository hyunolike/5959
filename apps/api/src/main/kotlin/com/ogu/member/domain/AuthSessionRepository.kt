package com.ogu.member.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AuthSessionRepository : JpaRepository<AuthSession, UUID> {
    /**
     * 현재 또는 직전 refresh 토큰 해시가 [hash]인 세션을 `SELECT ... FOR UPDATE`로 잠근다(data-model.md refresh 흐름도).
     * 같은 토큰으로 겹친 요청은 여기서 줄을 선다. 먼저 끝난 요청이 토큰을 교체해도 늦은 요청은 직전 해시로 같은 행을 다시
     * 찾는다(PostgreSQL은 잠금을 기다린 뒤 바뀐 행에 조건을 다시 적용한다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select s from AuthSession s where s.refreshTokenHash = :hash or s.previousRefreshTokenHash = :hash",
    )
    fun findByRefreshTokenHashForUpdate(
        @Param("hash") hash: String,
    ): AuthSession?
}
