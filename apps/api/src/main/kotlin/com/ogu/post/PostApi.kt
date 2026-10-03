package com.ogu.post

/**
 * post 모듈이 다른 모듈에 노출하는 파사드. 다른 모듈은 posts, comments, 공감 테이블을 직접 읽지 않는다.
 */
interface PostApi {
    /**
     * 몬스터를 만들 때 소급 반영할 공격(FR-006a). 작성자의 행동은 빼고, 살아 있는 글 공감, 회원별 첫 살아 있는 댓글,
     * 살아 있는 댓글 공감을 돌려준다.
     */
    fun attacksSoFar(postId: Long): List<Attack>
}
