package com.ogu.notification.domain

/** 알림 종류 7종(data-model.md "종류"). DB 체크 제약 `notification_type_check`와 같은 목록이다. */
enum class NotificationType {
    POST_COMMENT,
    POST_REPLY,
    COMMENT_REPLY,
    POST_LIKE,
    MONSTER_SPAWNED,
    MONSTER_DEFEATED,
    MONSTER_DEFEATED_TOGETHER,

    /** 005: 위험 판정 뒤 작성자에게 가는 도움 안내. 문구에 단계나 글 내용을 싣지 않는다. */
    SUPPORT_NOTICE,

    /** 005: 운영자가 숨김을 풀었다. */
    CONTENT_RESTORED,

    /** 005: 재검토 결과 숨김을 유지한다. */
    REVIEW_KEPT,

    /** 006: 내가 공격한 레이드 보스가 처치됐다. 글이 없고 누르면 레이드 화면으로 간다. */
    RAID_BOSS_DEFEATED,
}
