package com.ogu.post

/**
 * post 모듈이 다른 모듈에 노출하는 파사드. 다른 모듈은 posts, comments, 공감 테이블을 직접 읽지 않는다.
 */
interface PostApi {
    /** 살아 있는 글. 없거나 지운 글이면 null이다. */
    fun find(postId: Long): PostSummary?

    /**
     * 살아 있는 글을 키셋으로 한 쪽 읽는다(research R7). 보는 회원의 공감 여부도 같은 쿼리에서 함께 읽는다.
     * 커서가 올바르지 않으면 400 INVALID_REQUEST.
     */
    fun page(query: PostPageQuery): PostPage

    /** [postIds] 가운데 [memberId]가 공감한 글 ID. 쿼리 한 번이다. */
    fun likedPostIds(
        memberId: Long,
        postIds: Collection<Long>,
    ): Set<Long>

    /**
     * 몬스터를 만들 때 소급 반영할 공격(FR-006a). 작성자의 행동은 빼고, 살아 있는 글 공감, 회원별 첫 살아 있는 댓글,
     * 살아 있는 댓글 공감을 돌려준다.
     */
    fun attacksSoFar(postId: Long): List<Attack>

    /**
     * 살아 있는 글의 살아 있는 댓글(004 research R6). 알림의 받는 사람을 정할 때 쓴다. 답글이면 원 댓글과 그 주인도 준다.
     * 댓글이나 글을 지웠거나 없으면 null이다.
     */
    fun findComment(commentId: Long): CommentSummary?

    /**
     * 알림에 붙이는 글 미리보기(004 data-model.md). 지운 글도 [PostPreview.deleted]로 함께 준다. 없는 ID는 결과에서 빠진다.
     * 쿼리 한 번이다.
     */
    fun previews(postIds: Collection<Long>): Map<Long, PostPreview>

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
}
