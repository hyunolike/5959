/**
 * 고민 글 모듈. 글, 댓글과 답글, 글 공감과 댓글 공감을 관리한다.
 * 공감과 댓글은 {@link com.ogu.post.PostLiked}, {@link com.ogu.post.CommentCreated}, {@link com.ogu.post.CommentLiked}
 * 이벤트로 알리고, 글 작성은 {@link com.ogu.post.PostCreated}로 알린다. 몬스터를 모른다(research R6).
 * 다른 모듈은 {@link com.ogu.post.PostApi} 파사드로만 글을 읽는다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "member"})
package com.ogu.post;
