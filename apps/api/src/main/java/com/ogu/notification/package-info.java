/**
 * 알림 모듈. 댓글, 답글, 공감, 몬스터 생성과 처치를 알림으로 만들고, 목록과 읽음, 실시간(SSE) 전달을 맡는다.
 * {@code post}, {@code monster}의 이벤트를 받아 알림을 만들고, 다른 모듈은 {@code post}, {@code monster},
 * {@code member}의 파사드로만 읽는다. {@code emotion}을 모른다(몬스터 생성은 {@link com.ogu.monster.MonsterSpawned}로 받는다).
 * 파사드를 노출하지 않는다(HTTP API만). 인스턴스 간 신호는 Redis 채널로 보내고 내용은 DB에서 다시 읽는다(research R5).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "monster", "member", "safety", "raid"})
package com.ogu.notification;
