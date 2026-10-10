package com.ogu.post

/**
 * 다른 모듈이 고른 글 ID들을 글로 바꿔 주는 파사드(007 research R5, R7). 추천 모듈은 글의 ID만 알고, 보이는지와 카드에
 * 들어갈 내용은 여기서 읽는다. 그래서 숨김 규칙이 피드와 한곳에서 정해진다.
 */
interface PostSelectionApi {
    /**
     * [postIds] 가운데 다른 회원에게 보이는 글(지우지 않았고 숨기지 않은 글)의 ID. [exceptAuthorId]가 쓴 글은 뺀다.
     */
    fun visibleIds(
        postIds: Collection<Long>,
        exceptAuthorId: Long,
    ): Set<Long>

    /** [postIds]의 보이는 글을 준 순서대로 피드 한 쪽 모양으로 준다. 보이지 않는 글은 빠진다. 다음 쪽은 없다. */
    fun pageOf(
        postIds: List<Long>,
        viewerId: Long,
    ): PostPage

    /** ID가 [afterId]보다 큰 지우지 않은 글을 ID 순으로 [limit]개. 이미 있는 글의 임베딩을 만드는 일이 쓴다. */
    fun authorsAfter(
        afterId: Long,
        limit: Int,
    ): List<PostAuthorRef>
}

/** 글과 그 작성자. */
data class PostAuthorRef(
    val postId: Long,
    val authorId: Long,
)
