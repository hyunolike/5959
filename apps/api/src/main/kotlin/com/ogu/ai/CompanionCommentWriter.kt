package com.ogu.ai

/**
 * AI 친구의 댓글을 쓴다(009 research R11). 실패하거나 답이 규칙에 맞지 않으면 [CompanionCommentFailed]를 던진다.
 * 재시도는 호출하는 쪽(companion 모듈)이 맡는다. [key]는 로그에 남길 식별자다(예: `POST:12`). 글의 본문과 모델의 답은
 * 로그에 남기지 않는다.
 */
interface CompanionCommentWriter {
    fun write(
        key: String,
        input: CompanionCommentInput,
    ): String
}

/**
 * 댓글을 쓸 때 공급자에 보내는 것의 전부다(009 FR-012, 012 FR-010). 글의 본문, 글쓴이가 고른 말투, 분석된 감정, 답하는
 * 친구의 말씨, 그리고 같은 글에 앞서 남긴 AI 친구의 댓글뿐이다. 글쓴이의 닉네임, 직군, 경력, 회원 번호, 사람의 댓글이
 * 들어갈 자리를 두지 않는다. 자리를 더하려면 스펙부터 고친다.
 */
data class CompanionCommentInput(
    val content: String,
    val tone: CompanionTone,
    /** 분석이 끝났을 때만 있다. 없으면 요청에서 줄째로 빠진다. */
    val emotion: ClassifiedEmotion?,
    val voice: CompanionVoice,
    /** 같은 글에 앞서 남긴 AI 친구의 댓글(먼저 남긴 순). 같은 말을 되풀이하지 않게 하려고 보낸다. 없으면 그 구획이 빠진다. */
    val previous: List<String> = emptyList(),
)

/** 글쓴이가 바라는 댓글 말투. ai 모듈은 post 모듈 타입을 쓰지 않으므로 자체 enum을 쓰고, companion 모듈이 옮긴다. */
enum class CompanionTone(
    val label: String,
) {
    VENT_WITH_ME("대신 욕해주기"),
    COMFORT_ME("무조건 위로해주기"),
    WARM_ADVICE("따뜻한 조언해주기"),
    MAKE_ME_LAUGH("웃겨주기"),
}

/** AI 친구의 말씨(009 research R3). 말투가 무엇을 말할지를, 말씨가 어떻게 말할지를 정한다. */
enum class CompanionVoice(
    val label: String,
) {
    GENTLE("다정한 오구"),
    HEARTY("씩씩한 오구"),
    CALM("차분한 오구"),
}

/** 댓글 쓰기 실패. [kind]는 `companion_reaction.last_error`에 남길 분류이며, 모델의 답과 글의 본문은 담지 않는다. */
class CompanionCommentFailed(
    val kind: EmotionAnalysisFailed.Kind,
) : RuntimeException("AI 친구의 댓글 쓰기 실패: $kind")
