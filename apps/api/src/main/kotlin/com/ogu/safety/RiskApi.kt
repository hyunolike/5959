package com.ogu.safety

/**
 * 위험 판정을 다른 모듈에 알려 주는 파사드(009 research R7). AI 친구(companion 모듈)가 반응할 글을 고르고, AI가 쓴 댓글을
 * 올리기 전에 볼 때, 로그인하지 않은 방문자에게 보일 글을 고를 때(feed 모듈, 011) 쓴다. 판정을 바꾸지 않는다.
 */
interface RiskApi {
    /**
     * 글의 지금 판정. 저장하거나 고친 직후에는 키워드 단계만 있고 AI 분류는 아직이라 [PostRisk.settled]가 false다.
     * 판정 기록이 없는 글(안전 기능 전의 글)은 끝난 것으로 보고 글에 적힌 단계를 따른다.
     */
    fun postRisk(postId: Long): PostRisk

    /**
     * [postIds] 가운데 AI 분류까지 끝나 위험 없음으로 판정된 글. 가장 새 판정이 `DONE`이고 단계가 없음인 글만 든다.
     * AI를 기다리는 글, AI가 보지 못한 채 닫힌 글, 기한을 넘겨 키워드로만 닫힌 글, 판정 기록이 없는 글(안전 기능 전의
     * 글)은 들지 않는다. 로그인하지 않은 방문자에게 보일 글을 고를 때 쓴다(011 research R3). 상태와 단계를 한 문장으로
     * 읽으므로 분류 결과가 막 커밋된 글도 어긋나지 않는다. 쿼리 한 번이다.
     */
    fun clearedPostIds(postIds: Collection<Long>): Set<Long>

    /** [text]를 키워드 규칙으로만 본 단계. AI를 부르지 않고 메모리 안의 낱말 목록으로 판정한다. */
    fun keywordLevel(text: String): RiskLevel
}

/** [settled]가 false면 AI 분류가 끝나지 않았고(기다리는 중이거나 보지 못한 채 닫혔다) [level]은 그때까지의 키워드 단계다. */
data class PostRisk(
    val settled: Boolean,
    val level: RiskLevel,
)
