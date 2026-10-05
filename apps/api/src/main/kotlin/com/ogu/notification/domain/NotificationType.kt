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
}
