package com.ogu.emotion.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface EmotionAnalysisRepository : JpaRepository<EmotionAnalysis, Long> {
    /**
     * 시도할 차례가 된 PENDING 분석을 잠그며 가져온다. 다른 실행기가 잡고 있는 행은 기다리지 않고 건너뛴다
     * (research R2, `FOR UPDATE SKIP LOCKED`). 부분 인덱스 `emotion_analysis_pending_idx`를 탄다.
     */
    @Query(
        value = """
            select * from emotion_analysis
            where status = 'PENDING' and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked
        """,
        nativeQuery = true,
    )
    fun findDueForUpdateSkipLocked(
        @Param("now") now: Instant,
        @Param("limit") limit: Int,
    ): List<EmotionAnalysis>

    /** 한 글의 분석이 시도할 차례면 잠그며 가져온다. 다른 실행기가 잡고 있으면 null이다. */
    @Query(
        value = """
            select * from emotion_analysis
            where post_id = :postId and status = 'PENDING' and next_attempt_at <= :now
            for update skip locked
        """,
        nativeQuery = true,
    )
    fun findDueByPostIdForUpdateSkipLocked(
        @Param("postId") postId: Long,
        @Param("now") now: Instant,
    ): EmotionAnalysis?

    /** 시도 결과를 기록할 때 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmotionAnalysis e where e.postId = :postId")
    fun findByPostIdForUpdate(
        @Param("postId") postId: Long,
    ): EmotionAnalysis?

    /** 같은 `PostCreated`가 다시 전달돼도(Event Publication Registry 재발행) 행은 하나다. */
    @Modifying
    @Query(
        value = """
            insert into emotion_analysis (post_id, status, attempts, next_attempt_at, post_created_at)
            values (:postId, 'PENDING', 0, :now, :postCreatedAt)
            on conflict (post_id) do nothing
        """,
        nativeQuery = true,
    )
    fun insertPendingIfAbsent(
        @Param("postId") postId: Long,
        @Param("postCreatedAt") postCreatedAt: Instant,
        @Param("now") now: Instant,
    ): Int
}
