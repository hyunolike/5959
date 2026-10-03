package com.ogu.ai.infrastructure

import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.EmotionAnalyzer
import com.ogu.ai.EmotionClassification

/**
 * `ogu.ai.api-key`가 비어 있을 때(prod 밖) 쓰는 분석기. 외부 엔드포인트로 글 본문을 보내지 않고 바로 실패한다.
 * 재시도 일정을 따라 24시간 뒤 기본 몬스터가 된다. prod는 `ProdAiSettingsCheck`가 빈 키로 기동하지 못하게 막는다.
 */
class DisabledEmotionAnalyzer : EmotionAnalyzer {
    override fun analyze(
        postId: Long,
        content: String,
    ): EmotionClassification = throw EmotionAnalysisFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
}
