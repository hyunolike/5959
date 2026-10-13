package com.ogu.ai

/**
 * AI 친구의 고민 글을 쓴다(012 research R15). 실패하거나 답이 규칙에 맞지 않으면 [CompanionPostFailed]를 던진다.
 * 재시도는 호출하는 쪽(companion 모듈)이 맡는다. [key]는 로그에 남길 식별자다(예: `COMPANION_POST:12`). 모델의 답은
 * 로그에 남기지 않는다.
 */
interface CompanionPostWriter {
    fun write(
        key: String,
        input: CompanionPostInput,
    ): String
}

/**
 * 글을 쓸 때 공급자에 보내는 것의 전부다(012 FR-027). 소재, 글에 붙을 댓글 말투, 쓰는 친구의 말씨뿐이다. 회원의 글과 댓글,
 * 닉네임, 직군, 경력, 회원 번호가 들어갈 자리를 두지 않는다. 자리를 더하려면 스펙의 FR-027부터 고친다.
 */
data class CompanionPostInput(
    /** 소재의 한 줄 설명. 정해 둔 목록에서 고른 것이다. 회원의 글에서 가져오지 않는다. */
    val topic: String,
    val tone: CompanionTone,
    val voice: CompanionVoice,
)

/** 글쓰기 실패. [kind]는 `companion_post.last_error`에 남길 분류이며 모델의 답은 담지 않는다. */
class CompanionPostFailed(
    val kind: EmotionAnalysisFailed.Kind,
) : RuntimeException("AI 친구의 글쓰기 실패: $kind")
