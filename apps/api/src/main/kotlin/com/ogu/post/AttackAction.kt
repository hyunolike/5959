package com.ogu.post

/** 몬스터 HP를 줄이는 회원 행동. 감소량은 monster 모듈이 정한다(data-model.md). */
enum class AttackAction {
    POST_LIKE,
    COMMENT,
    COMMENT_LIKE,
}
