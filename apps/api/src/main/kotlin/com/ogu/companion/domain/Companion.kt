package com.ogu.companion.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * AI 친구 한 명(009 data-model.md `companion`). [memberId]는 `member`의 AI 회원이다. 이름이 `Companion`이면 클래스
 * 안에서 Kotlin의 companion object와 겹쳐 `Friend`로 부른다.
 */
data class Friend(
    val id: Int,
    val memberId: Long,
    val voice: Voice,
)

/** AI 친구의 말씨(009 research R3). 닉네임이 아니라 이 값에 말씨가 묶인다. 이름을 바꿔도 말씨는 그대로다. */
enum class Voice {
    GENTLE,
    HEARTY,
    CALM,
}

/** 반응하지 않고 닫은 까닭(009 data-model.md "건너뛴 까닭"). 운영자가 기록에서 읽는다. */
enum class SkipReason {
    /** 다른 회원이 먼저 댓글을 달았다. */
    HUMAN_COMMENT,

    /** 위기나 우려로 판정된 글이다. */
    RISK,

    /** 지웠거나 숨긴 글이다. */
    GONE,

    /** 올라온 지 24시간이 지났다. */
    EXPIRED,

    /** 그날의 몫이 떨어졌다. */
    DAILY_LIMIT,

    /** AI가 정해 둔 횟수만큼 답하지 못했다. */
    FAILED,

    /** AI의 답이 정해 둔 횟수만큼 검사에 걸렸다. */
    REJECTED,
}

/** `companion`은 마이그레이션의 시드로만 바뀐다. 한 번 읽어 메모리에 둔다. */
@Repository
class CompanionRepository(
    private val jdbcClient: JdbcClient,
) {
    private val friends: List<Friend> by lazy {
        jdbcClient
            .sql("select id, member_id, voice from companion order by id")
            .query { rs, _ -> Friend(rs.getInt("id"), rs.getLong("member_id"), Voice.valueOf(rs.getString("voice"))) }
            .list()
    }

    fun all(): List<Friend> = friends
}
