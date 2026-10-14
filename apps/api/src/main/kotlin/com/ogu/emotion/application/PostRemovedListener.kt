package com.ogu.emotion.application

import com.ogu.emotion.domain.EmotionAnalysisRepository
import com.ogu.post.PostRemoved
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 글이 지워지면 그 글의 분석 근거를 지운다(013 FR-008). 근거(`reason`)는 AI가 글을 읽고 쓴 한 줄이라 글의 내용이
 * 묻어 있다. 회원이 스스로 지운 글과 탈퇴한 회원의 글이 같은 이벤트로 온다. 감정과 강도는 남긴다.
 * 커밋 뒤 새 트랜잭션에서 돌고, 다시 와도 지울 것이 없을 뿐이다.
 */
@Component
class PostRemovedListener(
    private val repository: EmotionAnalysisRepository,
) {
    @ApplicationModuleListener
    fun on(event: PostRemoved) {
        repository.clearReason(event.postId)
    }
}
