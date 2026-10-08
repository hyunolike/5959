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
}
