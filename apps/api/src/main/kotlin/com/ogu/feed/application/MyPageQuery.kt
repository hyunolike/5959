package com.ogu.feed.application

import com.ogu.feed.presentation.dto.FeedPageResponse
import com.ogu.post.MyCommentPage
import com.ogu.post.PostActivityApi
import org.springframework.stereotype.Service

/**
 * 마이페이지의 세 목록(004 US3, research R12). 내가 쓴 글과 공감한 글은 피드와 같은 [FeedAssembler]로 조합해 쿼리가
 * 4개이고, 내 댓글은 post 모듈의 쿼리 하나다.
 */
@Service
class MyPageQuery(
    private val postActivityApi: PostActivityApi,
    private val feedAssembler: FeedAssembler,
) {
    fun posts(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): FeedPageResponse = feedAssembler.assemble(postActivityApi.pageByAuthor(memberId, cursor, size))

    fun comments(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): MyCommentPage = postActivityApi.pageCommentsByAuthor(memberId, cursor, size)

    fun likedPosts(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): FeedPageResponse = feedAssembler.assemble(postActivityApi.pageLikedBy(memberId, cursor, size))
}
