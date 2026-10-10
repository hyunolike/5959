/**
 * 피드 모듈. 글, 감정 분석, 몬스터, 작성자 정보를 파사드로 모아 피드와 글 상세를 응답한다.
 * 자기 테이블이 없다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "monster", "emotion", "member", "raid", "recommend"})
package com.ogu.feed;
