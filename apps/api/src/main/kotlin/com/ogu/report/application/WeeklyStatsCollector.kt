package com.ogu.report.application

import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.EmotionApi
import com.ogu.emotion.EmotionType
import com.ogu.monster.MonsterApi
import com.ogu.post.PostActivityApi
import com.ogu.post.PostReportApi
import com.ogu.post.ReportPostRef
import com.ogu.report.domain.WeeklyStats
import org.springframework.stereotype.Component

/** 회원 한 명의 한 주를 센 것과, 그 주에 위기로 판정된 글이 있었는지. */
data class CollectedWeek(
    val stats: WeeklyStats,
    val crisisWeek: Boolean,
)

/** 회원 한 명의 한 주를 세는 일. 발행하는 쪽은 이것만 본다. 테스트가 회원별 실패를 끼워 넣는 자리이기도 하다. */
fun interface WeeklyStatsSource {
    /** 그 주에 쓴 지우지 않은 글이 없으면 null이다. 그 회원은 리포트의 대상이 아니다. */
    fun collect(
        memberId: Long,
        week: WeekRange,
    ): CollectedWeek?
}

/**
 * 회원 한 명의 한 주를 센다(008 research R5). `posts`, `emotion_analysis`, `monsters`는 소유 모듈이 달라 파사드로 읽어
 * 메모리에서 센다. 쿼리는 글 수와 상관없이 5개다.
 */
@Component
class WeeklyStatsCollector(
    private val postReportApi: PostReportApi,
    private val postActivityApi: PostActivityApi,
    private val emotionApi: EmotionApi,
    private val monsterApi: MonsterApi,
) : WeeklyStatsSource {
    override fun collect(
        memberId: Long,
        week: WeekRange,
    ): CollectedWeek? {
        val posts = postReportApi.postsBetween(memberId, week.from, week.until)
        if (posts.isEmpty()) return null
        val emotions = emotionOf(posts)
        val counts = EmotionType.entries.associateWith { emotion -> emotions.values.count { it == emotion } }
        // 몬스터는 글을 쓴 주와 다른 주에 처치될 수 있다. 언제 쓴 글이든 그 주에 처치된 것을 센다
        val myPostIds = postActivityApi.liveRefsByAuthor(memberId).map { it.postId }
        val received = postReportApi.receivedBetween(memberId, week.from, week.until)
        return CollectedWeek(
            stats =
                WeeklyStats(
                    postCount = posts.size,
                    emotionCounts = counts,
                    unanalyzedCount = posts.size - emotions.size,
                    topEmotion = topEmotion(posts, emotions, counts),
                    defeatedCount = monsterApi.defeatedCountBetween(myPostIds, week.from, week.until),
                    receivedLikes = received.likes,
                    receivedComments = received.comments,
                ),
            crisisWeek = posts.any { it.riskLevel == CRISIS },
        )
    }

    /**
     * 분석이 끝난 글의 감정. 기다리는 글과, 24시간 동안 분석하지 못해 기본값을 받은 글(`DEFAULTED`)은 뺀다. 기본값은
     * 회원의 마음이 아니다.
     */
    private fun emotionOf(posts: List<ReportPostRef>): Map<Long, EmotionType> =
        emotionApi
            .findByPostIds(posts.map { it.postId })
            .filterValues { it.status == AnalysisStatus.ANALYZED }
            .mapNotNull { (postId, view) -> view.emotion?.let { postId to it } }
            .toMap()

    /** 수가 가장 큰 감정. 같으면 그 감정들 가운데 가장 최근에 쓴 글의 감정이다(마이페이지 통계와 같은 규칙). */
    private fun topEmotion(
        posts: List<ReportPostRef>,
        emotions: Map<Long, EmotionType>,
        counts: Map<EmotionType, Int>,
    ): EmotionType? {
        val max = counts.values.max()
        if (max == 0) return null
        return posts
            .filter { post -> emotions[post.postId]?.let { counts.getValue(it) == max } == true }
            .maxWith(compareBy<ReportPostRef> { it.createdAt }.thenBy { it.postId })
            .let { emotions.getValue(it.postId) }
    }

    private companion object {
        const val CRISIS = "CRISIS"
    }
}
