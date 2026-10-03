package com.ogu.post.application

import com.ogu.member.MemberApi
import com.ogu.post.CommentTone
import com.ogu.post.PostCreated
import com.ogu.post.domain.Post
import com.ogu.post.domain.PostAuthor
import com.ogu.post.domain.PostRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.lock.PostLock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class PostService(
    private val postRepository: PostRepository,
    private val postRateLimit: PostRateLimit,
    private val postLock: PostLock,
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

    /**
     * 본문이나 말투를 고친다(US4-AC1, FR-013). 작성 제한에 세지 않고, 몬스터와 감정은 그대로다. 둘 다 없으면 400,
     * 본문 규칙은 작성과 같다. 없거나 지운 글은 404 POST_NOT_FOUND, 남의 글은 403 NOT_AUTHOR.
     */
    @Transactional
    fun update(
        postId: Long,
        memberId: Long,
        content: String?,
        commentTone: CommentTone?,
    ) {
        if (content == null && commentTone == null) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "고칠 본문이나 댓글 말투를 주세요.")
        }
        // 행 잠금을 잡고 읽는다. 겹친 삭제가 먼저 커밋했으면 여기서 404가 되어 지운 글을 고친 뒤 204를 돌려주지 않는다.
        ownPost(postId, memberId, postRepository::findLiveForUpdate).edit(content, commentTone, now())
    }

    /**
     * 글을 지운다(US4-AC2, FR-013). 몬스터와 HP는 그대로 두고(지워도 HP는 돌아오지 않는다) 글만 어디에도 보이지 않게 한다.
     * `posts` 행을 UPDATE로 먼저 잠근 뒤 글 잠금([PostLock])을 잡는다(공감, 댓글과 같은 순서라 교착이 없다). 분석이 끝나
     * 몬스터를 만드는 MonsterFactory도 같은 글 잠금 안에서 글이 살아 있는지 보므로, 삭제와 겹쳐도 지운 글에 몬스터가
     * 새로 생기지 않는다. 그사이 다른 요청이 먼저 지웠으면 404다.
     */
    @Transactional
    fun delete(
        postId: Long,
        memberId: Long,
    ) {
        ownPost(postId, memberId, postRepository::findByIdAndDeletedAtIsNull)
        if (postRepository.softDelete(postId, now()) == 0) throw BusinessException(ErrorCode.POST_NOT_FOUND)
        postLock.lock(postId)
    }

    private fun ownPost(
        postId: Long,
        memberId: Long,
        findLive: (Long) -> Post?,
    ): Post {
        val post = findLive(postId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
        if (post.authorId != memberId) throw BusinessException(ErrorCode.NOT_AUTHOR)
        return post
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
