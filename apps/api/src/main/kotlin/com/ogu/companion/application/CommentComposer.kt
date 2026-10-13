package com.ogu.companion.application

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.CompanionCommentFailed
import com.ogu.ai.CompanionCommentInput
import com.ogu.ai.CompanionCommentWriter
import com.ogu.ai.CompanionTone
import com.ogu.ai.CompanionVoice
import com.ogu.ai.EmotionAnalysisFailed.Kind
import com.ogu.ai.RiskClassifier
import com.ogu.companion.domain.Voice
import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.EmotionApi
import com.ogu.post.CompanionTarget
import com.ogu.safety.RiskApi
import com.ogu.safety.RiskLevel
import com.ogu.shared.text.ContentMask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

sealed interface Draft {
    /** 검사를 통과한 댓글. */
    data class Written(
        val comment: String,
    ) : Draft

    /**
     * 댓글을 얻지 못했다. [kind]는 기록에 남길 분류다. [rejected]면 답은 왔지만 검사에 걸린 것이고, [notSent]면 회로가
     * 열려 호출이 공급자에 나가지 않은 것이다.
     */
    data class Failed(
        val kind: String,
        val rejected: Boolean = false,
        val notSent: Boolean = false,
    ) : Draft
}

/**
 * AI에 댓글을 청하고 올리기 전에 검사한다(009 research R6, R11). 공급자에 보내는 것은 글의 본문, 글쓴이가 고른 말투, 분석된
 * 감정, 친구의 말씨, 그리고 같은 글에 앞서 남긴 보이는 AI 친구의 댓글뿐이다(012 FR-010). 사람의 댓글은 보내지 않는다.
 * 감정은 분석이 끝났을 때만 보낸다. 글의 본문과 AI의 답은 로그에 남기지 않는다.
 */
@Component
class CommentComposer(
    private val writer: CompanionCommentWriter,
    private val emotionApi: EmotionApi,
    private val gate: CommentGate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compose(
        target: CompanionTarget,
        voice: Voice,
    ): Draft {
        // 숨겨진 댓글은 보내지 않는다. 숨겨진 까닭이 있는 글을 다시 공급자에 보내지 않는다(012 research R6)
        val previous = target.companionComments.filter { it.visible }.map { it.content }
        return when (val draft = write(target, voice, previous)) {
            is Draft.Failed -> draft
            is Draft.Written ->
                gate.rejectionOf(target.postId, draft.comment, previous)?.let { Draft.Failed(it, rejected = true) }
                    ?: draft
        }
    }

    @Suppress("TooGenericExceptionCaught") // 댓글 쓰기의 어떤 실패도 재시도 일정으로 넘긴다(constitution V)
    private fun write(
        target: CompanionTarget,
        voice: Voice,
        previous: List<String>,
    ): Draft {
        val key = "POST:${target.postId}"
        return try {
            Draft.Written(writer.write(key, inputOf(target, voice, previous)))
        } catch (e: CompanionCommentFailed) {
            failed(e.kind)
        } catch (e: RuntimeException) {
            log.warn("AI 친구의 댓글 쓰기가 예상하지 못한 예외를 던졌습니다: target={}, cause={}", key, e.javaClass.simpleName)
            failed(Kind.UPSTREAM_ERROR)
        }
    }

    private fun failed(kind: Kind): Draft.Failed =
        Draft.Failed(kind.name, rejected = kind == Kind.INVALID_RESPONSE, notSent = kind == Kind.CIRCUIT_OPEN)

    private fun inputOf(
        target: CompanionTarget,
        voice: Voice,
        previous: List<String>,
    ): CompanionCommentInput {
        val analysis = emotionApi.findByPostIds(listOf(target.postId))[target.postId]
        // 24시간 동안 분석하지 못해 기본값을 받은 감정은 글에서 읽은 것이 아니라 보내지 않는다
        val emotion = analysis?.takeIf { it.status == AnalysisStatus.ANALYZED }?.emotion
        return CompanionCommentInput(
            content = target.content,
            tone = CompanionTone.valueOf(target.commentTone.name),
            emotion = emotion?.let { ClassifiedEmotion.valueOf(it.name) },
            voice = CompanionVoice.valueOf(voice.name),
            previous = previous,
        )
    }
}

/**
 * AI가 쓴 댓글을 올리기 전에 보는 검사(009 research R6, FR-008). 사람의 댓글은 저장한 뒤에 판정해 위기면 숨기지만, AI의
 * 댓글은 순서를 바꿔 판정한 뒤에 저장한다. 걸린 댓글은 한 번도 보이지 않는다(SC-005). 모양과 규칙은 `ai` 모듈의 검증기가
 * 이미 봤다.
 */
@Component
class CommentGate(
    private val contentMask: ContentMask,
    private val riskApi: RiskApi,
    private val riskClassifier: RiskClassifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 걸린 검사의 이름. 통과하면 null이다. 싼 검사부터 본다. [previous]는 같은 글에 앞서 남긴 AI 친구의 댓글이다. 그 문장을
     * 되풀이한 답은 올리지 않는다(012 FR-010).
     */
    fun rejectionOf(
        postId: Long,
        comment: String,
        previous: List<String> = emptyList(),
    ): String? =
        when {
            Repetition.repeats(comment, previous) -> REPEATED
            // 다른 회원에게 가려져 보일 낱말이 있다
            contentMask.mask(comment) != comment -> PROFANITY
            riskApi.keywordLevel(comment) != RiskLevel.NONE -> KEYWORD_RISK
            classifiedRisky(postId, comment) -> AI_RISK
            else -> null
        }

    /**
     * AI 위험 분류가 실패하면 키워드 판정만으로 올린다(FR-008, 원칙 IV). 올린 뒤에는 사람의 댓글과 같은 분류가 다시 돌고
     * 실패하면 재시도하므로, 그때 걸리면 숨겨진다.
     */
    @Suppress("TooGenericExceptionCaught") // 분류기의 어떤 실패에도 키워드 판정이 남는다
    private fun classifiedRisky(
        postId: Long,
        comment: String,
    ): Boolean =
        try {
            riskClassifier.classify("COMPANION:$postId", comment) != ClassifiedRisk.NONE
        } catch (e: RuntimeException) {
            log.warn("AI 친구의 댓글을 AI로 분류하지 못해 키워드 판정만으로 올립니다: postId={}, cause={}", postId, e.javaClass.simpleName)
            false
        }

    private companion object {
        const val REPEATED = "REPEATED"
        const val PROFANITY = "PROFANITY"
        const val KEYWORD_RISK = "KEYWORD_RISK"
        const val AI_RISK = "AI_RISK"
    }
}

/**
 * 뒤따르는 AI 친구의 댓글이 앞 댓글을 되풀이하는지 본다(012 research R6). 글자와 숫자만 남겨 견준다. 띄어쓰기와 문장
 * 부호만 다른 것은 같은 말이다. 전체가 같거나, 다듬은 길이가 [MIN_SENTENCE]자 이상인 문장 하나가 앞 댓글의 문장과 같으면
 * 되풀이다. 짧은 맞장구("그랬군요")는 겹쳐도 되풀이로 보지 않는다.
 */
internal object Repetition {
    private const val MIN_SENTENCE = 8
    private val SENTENCE_END = Regex("[.!?。…\\n]+")

    fun repeats(
        comment: String,
        previous: List<String>,
    ): Boolean {
        if (previous.isEmpty()) return false
        val whole = normalized(comment)
        val earlier = previous.flatMap(::sentencesOf).toSet()
        return previous.any { normalized(it) == whole } || sentencesOf(comment).any { it in earlier }
    }

    private fun sentencesOf(text: String): List<String> {
        val sentences = text.split(SENTENCE_END).map(::normalized)
        return sentences.filter { it.length >= MIN_SENTENCE }
    }

    private fun normalized(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
}
