/**
 * 레이드 모듈. 모든 회원이 보스 한 마리를 함께 공격한다. 살아 있는 보스의 HP와 회원별 기여는 Redis에 두고
 * Lua 스크립트 하나로 바꾸며, Postgres에는 뒤따라 적는다. 처치는 Postgres에 적은 뒤에 알린다.
 * 실시간 전달은 {@code shared}의 {@code TopicBroadcaster}를 거친다. 글의 몬스터({@code monster})와는
 * 서로 영향을 주지 않는다(006 research R1~R7).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "emotion", "member"})
package com.ogu.raid;
