package com.ogu.post

/** 글쓴이가 바라는 댓글 말투. */
enum class CommentTone(
    val label: String,
) {
    VENT_WITH_ME("대신 욕해주기"),
    COMFORT_ME("무조건 위로해주기"),
    WARM_ADVICE("따뜻한 조언해주기"),
    MAKE_ME_LAUGH("웃겨주기"),
}
