package com.ogu.post

/**
 * post 모듈이 다른 모듈에 노출하는 파사드. 다른 모듈은 posts, comments, 공감 테이블을 직접 읽지 않는다.
 */
interface PostApi {
    /**
     * 지우지 않은 글. 없거나 지운 글이면 null이다. 숨긴 글도 돌려준다([PostSummary.hidden]). 감정 분석과 몬스터처럼
     * 숨김과 상관없이 글을 다루는 모듈이 쓴다. 다른 회원에게 보여 줄 것은 [findVisible]이나 [findForViewer]로 읽는다.
     */
    fun find(postId: Long): PostSummary?

    /**
     * 다른 회원에게 보이는 글(005 research R5, R8). 없거나, 지웠거나, 숨긴 글이면 null이다. 알림의 대상 확인이 이것 하나로
     * 글의 노출 여부를 판단한다(ADR-0005).
     */
    fun findVisible(postId: Long): PostSummary?

    /** [viewerId]에게 보이는 글: 지우지 않았고, 숨기지 않았거나 자기 글이다. 글 상세가 쓴다. */
    fun findForViewer(
        postId: Long,
        viewerId: Long,
    ): PostSummary?

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
     * 다른 회원에게 보이는 글의 보이는 댓글(004 research R6, 005 research R8). 알림의 받는 사람을 정할 때 쓴다. 답글이면
     * 원 댓글과 그 주인도 준다. 댓글이나 글을 지웠거나 숨겼거나 없으면 null이다.
     */
    fun findComment(commentId: Long): CommentSummary?

    /**
     * 알림에 붙이는 글 미리보기(004 data-model.md). 지운 글도 [PostPreview.deleted]로 함께 준다. 숨긴 글은 지운 글과 같이
     * `deleted = true`로 주되, [viewerId]가 그 글의 작성자면 그대로 준다(005 research R8). 없는 ID는 결과에서 빠진다.
     * 쿼리 한 번이다.
     */
    fun previews(
        postIds: Collection<Long>,
        viewerId: Long,
    ): Map<Long, PostPreview>
}
