package com.ogu.safety.application

import com.ogu.post.ContentType
import com.ogu.post.PostApi
import com.ogu.safety.PostRisk
import com.ogu.safety.RiskApi
import com.ogu.safety.RiskLevel
import com.ogu.safety.domain.RiskAssessmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [RiskApi]의 구현. 판정은 가장 새 `risk_assessment` 행이 말한다(고치면 새 행이 생긴다). 끝난 판정은 AI 분류가 끝났거나
 * (`DONE`) 기한을 넘겨 키워드로 확정된 것(`FALLBACK`)뿐이다. 기다리는 중(`PENDING`)과 AI가 보지 못한 채 닫힌 것
 * (`SUPERSEDED`)은 끝난 것이 아니다. 모르면 끝나지 않은 쪽으로 답한다(원칙 IV).
 */
@Service
class RiskQueryService(
    private val assessments: RiskAssessmentRepository,
    private val postApi: PostApi,
    private val termCache: TermCache,
) : RiskApi {
    @Transactional(readOnly = true)
    override fun postRisk(postId: Long): PostRisk {
        val latest = assessments.latest(ContentType.POST, postId)
        if (latest != null) return PostRisk(settled = latest.status in SETTLED, level = latest.level)
        // 안전 기능 전에 쓰인 글은 기록이 없다. 글에 적힌 단계가 전부다
        val written = postApi.find(postId)?.riskLevel?.let(RiskLevel::of) ?: RiskLevel.NONE
        return PostRisk(settled = true, level = written)
    }

    @Transactional(readOnly = true)
    override fun clearedPostIds(postIds: Collection<Long>): Set<Long> =
        if (postIds.isEmpty()) emptySet() else assessments.clearedIds(ContentType.POST, postIds.toSet())

    override fun keywordLevel(text: String): RiskLevel = KeywordRule.level(text, termCache.terms())

    private companion object {
        val SETTLED = setOf("DONE", "FALLBACK")
    }
}
