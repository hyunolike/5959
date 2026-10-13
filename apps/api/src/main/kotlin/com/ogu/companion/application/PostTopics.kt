package com.ogu.companion.application

/** AI 친구가 글감으로 쓰는 직장 생활의 상황 하나. [description]이 AI에 건네는 한 줄이다. */
data class PostTopic(
    val key: String,
    val description: String,
)

/**
 * AI 친구가 쓰는 글의 소재(012 research R14). 직장인이 흔히 겪는 일을 사실 한 줄로 적었다. 감정과 말씨는 프롬프트가 입힌다.
 * 회원의 글에서 뽑지 않는다(FR-027). 죽음, 자해, 질병, 특정 회사나 업종이 드러나는 일은 넣지 않는다. 열쇠는
 * `companion_post.topic`에 남으므로 바꾸지 않는다(40자 이하). 소재를 빼면 그 열쇠의 쓰는 중인 기록은 그만둔다.
 */
object PostTopics {
    val ALL: List<PostTopic> =
        listOf(
            PostTopic("MEETING_SILENCE", "회의에서 준비한 말을 끝내 꺼내지 못했다."),
            PostTopic("MEETING_OVERRUN", "한 시간이라던 회의가 세 시간째 끝나지 않았다."),
            PostTopic("LATE_REQUEST", "퇴근 십 분 전에 내일 아침까지 해 달라는 일을 받았다."),
            PostTopic("CREDIT_TAKEN", "내가 정리한 자료가 다른 사람의 이름으로 보고됐다."),
            PostTopic("VAGUE_FEEDBACK", "보고서를 다시 해 오라는데 무엇이 문제인지는 듣지 못했다."),
            PostTopic("PUBLIC_SCOLDING", "여러 사람 앞에서 실수를 지적받았다."),
            PostTopic("SMALL_MISTAKE", "메일을 잘못된 사람에게 보내고 하루 종일 마음이 쓰였다."),
            PostTopic("LUNCH_ALONE", "점심을 같이 먹을 사람이 없어 자리에서 혼자 먹었다."),
            PostTopic("WEEKEND_MESSAGE", "주말에 온 업무 메시지에 답해야 할지 한참 망설였다."),
            PostTopic("MONDAY_DREAD", "일요일 밤마다 내일 출근할 생각에 잠이 오지 않는다."),
            PostTopic("COMPARISON", "같이 들어온 동기가 먼저 인정받는 것을 보고 마음이 복잡했다."),
            PostTopic("NO_PRAISE", "큰일을 끝냈는데 수고했다는 말 한마디가 없었다."),
            PostTopic("OVERTIME_HABIT", "이번 주 내내 야근했는데 일이 줄지 않는다."),
            PostTopic("HANDOVER_GAP", "인수인계 없이 맡은 일에서 모르는 것이 계속 나온다."),
            PostTopic("QUESTION_FEAR", "모르는 것을 물어보면 귀찮아할까 봐 묻지 못했다."),
            PostTopic("CHANGED_PLAN", "거의 다 한 일의 방향이 하루아침에 바뀌었다."),
            PostTopic("PRESENTATION_NERVES", "내일 발표가 있는데 준비해도 준비한 것 같지 않다."),
            PostTopic("VACATION_GUILT", "휴가를 쓰겠다고 말하는 것이 눈치가 보인다."),
            PostTopic("TEAM_DINNER", "가고 싶지 않은 회식을 거절하지 못했다."),
            PostTopic("QUIET_TEAMMATE", "옆자리 동료가 며칠째 인사를 받지 않는다."),
            PostTopic("ROLE_UNCLEAR", "내 일이 어디까지인지 몰라 남의 일까지 하고 있다."),
            PostTopic("JOB_CHANGE_DOUBT", "이직을 생각하지만 지금 떠나는 것이 맞는지 모르겠다."),
            PostTopic("REVIEW_SEASON", "평가 면담을 앞두고 한 해 동안 한 일이 떠오르지 않는다."),
            PostTopic("REMOTE_ISOLATION", "재택으로 일한 날은 하루 종일 한마디도 하지 않았다."),
            PostTopic("FIRST_LEAD", "처음으로 일을 맡아 이끌게 됐는데 무엇부터 해야 할지 모르겠다."),
            PostTopic("INTERRUPTED_WORK", "집중하려고 하면 누가 불러서 오늘도 내 일을 못 했다."),
            PostTopic("SLOW_GROWTH", "삼 년째 같은 일을 하면서 늘고 있는지 모르겠다."),
            PostTopic("APOLOGY_UNSAID", "동료에게 날카롭게 말하고 사과하지 못한 채 퇴근했다."),
            PostTopic("COMMUTE_DRAIN", "출퇴근에 하루 세 시간을 쓰고 나면 아무것도 할 힘이 없다."),
            PostTopic("SUNDAY_WORK", "쉬는 날에도 밀린 일을 생각하느라 쉬지 못했다."),
        )

    private val BY_KEY = ALL.associateBy { it.key }

    fun find(key: String): PostTopic? = BY_KEY[key]
}
