package com.ogu.post

/**
 * 회원 한 명의 활동(쓴 글, 댓글, 공감)을 읽는 post 모듈 파사드(004 research R12). 마이페이지의 세 목록과 감정 통계가 쓴다.
 * 글 하나를 다루는 조회는 [PostApi]에 있다.
 */
interface PostActivityApi {
    /**
     * [authorId]가 쓴 살아 있는 글을 최신순(`id DESC`) 키셋으로 한 쪽 읽는다(004 research R12). 공감 여부는 글쓴이 자신의 것이다.
     * 커서가 올바르지 않거나 [size]가 1~[PostPageQuery.MAX_SIZE] 밖이면 400 INVALID_REQUEST.
     */
    fun pageByAuthor(
        authorId: Long,
        cursor: String?,
        size: Int,
    ): PostPage

    /**
     * [authorId]가 쓴 살아 있는 댓글과 답글을 최신순(`id DESC`) 키셋으로 한 쪽 읽는다(004 research R12). 지운 글의 댓글은 빠진다.
     * 커서와 [size]의 규칙은 [pageByAuthor]와 같다.
     */
    fun pageCommentsByAuthor(
        authorId: Long,
        cursor: String?,
        size: Int,
    ): MyCommentPage

    /**
     * [memberId]가 공감한 살아 있는 글을 공감한 시각의 최신순(`(created_at DESC, post_id DESC)`) 키셋으로 한 쪽 읽는다
     * (004 research R12). 취소한 공감은 행이 없어 빠진다. 커서와 [size]의 규칙은 [pageByAuthor]와 같다.
     */
    fun pageLikedBy(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): PostPage

    /** [authorId]가 쓴 살아 있는 글의 ID와 작성 시각 전부(004 research R12). 감정 통계가 쓴다. 쿼리 한 번이다. */
    fun liveRefsByAuthor(authorId: Long): List<PostRef>

    /** [postIds] 가운데 살아 있는 글의 ID. 쿼리 한 번이다. */
    fun liveIds(postIds: Collection<Long>): Set<Long>
}
