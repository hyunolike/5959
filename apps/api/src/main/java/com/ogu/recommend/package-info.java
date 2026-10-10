/**
 * 추천 모듈. 글의 임베딩을 만들어 저장하고, 글 하나와 뜻이 가까운 글을 찾는다. 임베딩이 없거나 가까운 글이 없으면 같은 감정의
 * 최근 글로 대신한다. 글의 ID만 돌려주고, 카드로 조립하는 일은 {@code feed}가 피드와 같은 길로 한다.
 * {@code posts}는 {@link com.ogu.post.PostApi}로만 읽는다(007 research R1, R5).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "ai", "emotion"})
package com.ogu.recommend;
