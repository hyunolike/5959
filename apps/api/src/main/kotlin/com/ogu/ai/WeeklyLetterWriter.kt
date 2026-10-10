package com.ogu.ai

/**
 * 주간 리포트의 짧은 편지를 쓴다(008 research R7). 실패하면 [WeeklyLetterFailed]를 던진다. 재시도는 호출하는 쪽
 * (report 모듈)이 맡는다. [key]는 로그에 남길 식별자다(예: `REPORT:12`). 수치와 편지는 로그에 남기지 않는다.
 */
interface WeeklyLetterWriter {
    fun write(
        key: String,
        input: WeeklyLetterInput,
    ): String
}

/**
 * 편지를 쓸 때 공급자에 보내는 것의 전부다(008 FR-010). 집계한 수치만 있다. 글의 본문, 닉네임, 직군, 경력, 회원 번호가
 * 들어갈 자리를 두지 않는다. 자리를 더하려면 스펙의 FR-010부터 고친다.
 *
 * 앞 주의 수치도 보내지 않는다. 실제 모델은 두 주를 견주다 틀리거나(줄었는데 늘었다고) 근거 없이 평가했다
 * (research R7). 앞 주와의 차이는 화면이 저장된 수치로 정확히 보인다.
 */
data class WeeklyLetterInput(
    val postCount: Int,
    val emotionCounts: Map<ClassifiedEmotion, Int>,
    val unanalyzedCount: Int,
    val topEmotion: ClassifiedEmotion?,
    val defeatedCount: Int,
    val receivedLikes: Int,
    val receivedComments: Int,
) {
    /** 편지에 나와도 되는 숫자. 여기에 없는 숫자가 편지에 있으면 모델이 지어낸 것이다. */
    fun numbers(): Set<Int> =
        buildSet {
            add(postCount)
            addAll(emotionCounts.values)
            add(unanalyzedCount)
            add(defeatedCount)
            add(receivedLikes)
            add(receivedComments)
        }
}

/** 편지 쓰기 실패. [kind]는 `weekly_report.letter_last_error`에 남길 분류이며, 모델의 답은 담지 않는다. */
class WeeklyLetterFailed(
    val kind: EmotionAnalysisFailed.Kind,
) : RuntimeException("편지 쓰기 실패: $kind")
