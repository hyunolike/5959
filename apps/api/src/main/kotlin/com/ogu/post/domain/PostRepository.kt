package com.ogu.post.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PostRepository : JpaRepository<Post, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Post?

    /**
     * 수정할 글을 행 잠금과 함께 읽는다. 겹친 삭제가 먼저 잠갔으면 그 커밋을 기다렸다가 다시 평가해 null이 된다(지운 글 수정은
     * 404). 수정이 먼저 잡으면 삭제가 기다린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id and p.deletedAt is null")
    fun findLiveForUpdate(
        @Param("id") id: Long,
    ): Post?

    /** 작성 제한(research R9)용. 지운 글도 센다(FR-018). */
    fun countByAuthorIdAndCreatedAtAfter(
        authorId: Long,
        since: Instant,
    ): Long

    fun findFirstByAuthorIdAndCreatedAtAfterOrderByCreatedAtAsc(
        authorId: Long,
        since: Instant,
    ): Post?

    /** 댓글 수를 한 문장으로 바꾼다. 동시에 단 댓글끼리 서로의 증가를 덮어쓰지 않는다. */
    @Modifying
    @Query(value = "update posts set comment_count = comment_count + :delta where id = :id", nativeQuery = true)
    fun addCommentCount(
        @Param("id") id: Long,
        @Param("delta") delta: Int,
    ): Int

    /**
     * 살아 있는 글을 지운다. 이 UPDATE가 posts 행을 잠근다(잠금 순서: 행 먼저, 글 잠금은 그다음). 이미 지웠으면 0이다.
     */
    @Modifying
    @Query(
        value = "update posts set deleted_at = :now, updated_at = :now where id = :id and deleted_at is null",
        nativeQuery = true,
    )
    fun softDelete(
        @Param("id") id: Long,
        @Param("now") now: Instant,
    ): Int
}
