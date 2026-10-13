/**
 * 피드 모듈. 글, 감정 분석, 몬스터, 작성자 정보를 파사드로 모아 피드와 글 상세를 응답한다.
 * 자기 테이블이 없다. 로그인하지 않은 방문자에게 보일 글을 고를 때만 {@code safety}에 위험 판정이 끝났는지 묻는다(011).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "monster", "emotion", "member", "raid", "recommend", "safety"})
package com.ogu.feed;
