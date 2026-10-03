package com.ogu.post.application

import com.ogu.member.MemberApi
import com.ogu.post.CommentTone
import com.ogu.post.PostCreated
import com.ogu.post.domain.Post
import com.ogu.post.domain.PostAuthor
import com.ogu.post.domain.PostRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class PostService(
    private val postRepository: PostRepository,
    private val postRateLimit: PostRateLimit,
    private val memberApi: MemberApi,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * 글을 저장하고 같은 트랜잭션에서 [PostCreated]를 발행한다(research R2). 감정 분석은 커밋 뒤 emotion 모듈이 비동기로 한다.
     * 작성자 직군과 경력은 지금 프로필을 스냅숏으로 남긴다(research R7).
     */
    @Transactional
    fun create(
        authorId: Long,
        content: String,
        commentTone: CommentTone,
    ): Long {
        // 본문 검증을 먼저 해서 잘못된 요청이 작성 제한 잠금을 잡지 않게 한다.
        val normalized = Post.normalizeContent(content)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        postRateLimit.check(authorId, now)

        val profile = memberApi.getMember(authorId)
        val jobRole = profile.jobRole ?: throw BusinessException(ErrorCode.ONBOARDING_REQUIRED)
        val careerYear = profile.careerYear ?: throw BusinessException(ErrorCode.ONBOARDING_REQUIRED)

        val author = PostAuthor(authorId, jobRole, careerYear)
        val post = postRepository.save(Post.write(author, normalized, commentTone, now))
        events.publishEvent(PostCreated(post.id, authorId, post.content, post.createdAt))
        return post.id
    }
}
