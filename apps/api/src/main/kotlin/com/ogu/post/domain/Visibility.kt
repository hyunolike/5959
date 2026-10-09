package com.ogu.post.domain

/**
 * 글과 댓글이 누구에게 보이는지의 SQL 조건(005 research R5). 조건이 흩어지면 한 곳만 빠져도 숨긴 글이 새어 나가므로,
 * `posts`와 `comments`를 읽는 JdbcClient 쿼리는 모두 여기의 조각을 쓴다. JPA 쿼리는 리포지토리의 `findVisible`,
 * `findOwnedOrVisible`이 같은 조건을 JPQL로 적는다.
 */
internal object Visibility {
    /** 별칭 없는 한 테이블 쿼리용. 다른 회원에게 보인다: 지우지 않았고 숨기지 않았다. */
    const val VISIBLE = "deleted_at is null and hidden_at is null"

    /** 별칭 없는 한 테이블 쿼리용. 지우지 않았다(숨긴 것 포함). */
    const val NOT_DELETED = "deleted_at is null"

    /** 지우지 않았다. 숨긴 것도 포함한다. 작성자 자신의 목록과, 숨김과 상관없이 글을 다루는 내부 조회가 쓴다. */
    fun notDeleted(alias: String): String = "$alias.deleted_at is null"

    /** 다른 회원에게 보인다. */
    fun visible(alias: String): String = "$alias.deleted_at is null and $alias.hidden_at is null"

    /** [viewerParam](이름 붙인 파라미터)의 회원에게 보인다: 지우지 않았고, 숨기지 않았거나 자기 것이다. */
    fun ownedOrVisible(
        alias: String,
        viewerParam: String,
    ): String = "$alias.deleted_at is null and ($alias.hidden_at is null or $alias.author_id = :$viewerParam)"
}
