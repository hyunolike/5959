/**
 * 레이드 모듈. 모든 회원이 보스 한 마리를 함께 공격한다. 살아 있는 보스의 HP와 회원별 기여는 서버 메모리
 * ({@code RaidLiveStore})에 두고 잠금 하나 안에서 바꾸며, Postgres에는 뒤따라 적는다(018 research R5). 처치는 Postgres에 적은 뒤에 알린다.
 * 실시간 전달은 {@code shared}의 {@code TopicBroadcaster}를 거친다. 글의 몬스터({@code monster})와는
 * 서로 영향을 주지 않는다(006 research R1~R7).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "emotion", "member"})
package com.ogu.raid;
