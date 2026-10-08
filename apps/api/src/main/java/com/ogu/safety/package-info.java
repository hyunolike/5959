/**
 * 안전 모듈. 글과 댓글의 위험 신호를 판정하고(키워드 규칙과 AI 분류), 위기면 숨기고 작성자에게 알린다.
 * 신고와 재검토 요청을 받고, 운영자가 조회하고 처리한다. 욕설 가리기({@code shared}의 {@code ContentMask})를 구현한다.
 * 숨김 상태는 {@code post}의 열이고 {@link com.ogu.post.PostModerationApi}로만 바꾼다.
 * {@code post}는 이 모듈을 모른다. {@code post}가 낸 이벤트를 받아 판정한다(005 research R1, R2).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "ai", "member"})
package com.ogu.safety;
