/**
 * 몬스터 모듈. 감정 분석 결과로 몬스터를 만들고, 공감과 댓글 이벤트로 HP를 줄인다.
 * 다른 모듈은 {@link com.ogu.monster.MonsterApi}로 몬스터 상태를 읽고, 처치는 {@link com.ogu.monster.MonsterDefeated}로 알린다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "emotion"})
package com.ogu.monster;
