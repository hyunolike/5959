package com.ogu.safety.application

import com.ogu.post.ContentType
import com.ogu.post.PostApi
import com.ogu.safety.PostRisk
import com.ogu.safety.RiskApi
import com.ogu.safety.RiskLevel
import com.ogu.safety.domain.RiskAssessmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** [RiskApi]의 구현. 판정은 가장 새 `risk_assessment` 행이 말한다(고치면 새 행이 생긴다). */
@Service
class RiskQueryService(
    private val assessments: RiskAssessmentRepository,
    private val postApi: PostApi,
    private val termCache: TermCache,
) : RiskApi {
    @Transactional(readOnly = true)
    override fun postRisk(postId: Long): PostRisk {
        val latest = assessments.latest(ContentType.POST, postId)
        if (latest != null) return PostRisk(settled = latest.status != PENDING, level = latest.level)
        // 안전 기능 전에 쓰인 글은 기록이 없다. 글에 적힌 단계가 전부다
        val written = postApi.find(postId)?.riskLevel?.let(RiskLevel::of) ?: RiskLevel.NONE
        return PostRisk(settled = true, level = written)
    }

    override fun keywordLevel(text: String): RiskLevel = KeywordRule.level(text, termCache.terms())

    private companion object {
        const val PENDING = "PENDING"
    }
}
