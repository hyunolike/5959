package com.ogu.emotion.application

import com.ogu.emotion.EmotionApi
import com.ogu.emotion.EmotionType
import com.ogu.emotion.EmotionView
import com.ogu.emotion.domain.EmotionAnalysisRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class EmotionQueryService(
    private val repository: EmotionAnalysisRepository,
) : EmotionApi {
    override fun findByPostIds(postIds: Collection<Long>): Map<Long, EmotionView> {
        if (postIds.isEmpty()) return emptyMap()
        return repository
            .findAllById(postIds.toSet())
            .associate { it.postId to EmotionView(it.status, it.emotion, it.intensity) }
    }

    override fun recentPostIds(
        emotion: EmotionType,
        limit: Int,
    ): List<Long> = repository.findRecentPostIdsByEmotion(emotion.name, limit)
}
