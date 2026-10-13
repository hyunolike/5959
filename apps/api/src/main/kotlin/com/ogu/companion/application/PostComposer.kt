package com.ogu.companion.application

import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.CompanionPostFailed
import com.ogu.ai.CompanionPostInput
import com.ogu.ai.CompanionPostWriter
import com.ogu.ai.CompanionTone
import com.ogu.ai.CompanionVoice
import com.ogu.ai.EmotionAnalysisFailed.Kind
import com.ogu.ai.RiskClassifier
import com.ogu.companion.domain.PostAttempt
import com.ogu.companion.domain.Voice
import com.ogu.safety.RiskApi
import com.ogu.safety.RiskLevel
import com.ogu.shared.text.ContentMask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

sealed interface Composed {
    /** 검사를 통과한 글. */
    data class Written(
        val content: String,
    ) : Composed

    /**
     * 글을 얻지 못했다. [kind]는 기록에 남길 분류다. [rejected]면 답은 왔지만 검사에 걸린 것이고, [notSent]면 회로가 열려
     * 호출이 공급자에 나가지 않은 것이다.
     */
    data class Failed(
        val kind: String,
        val rejected: Boolean = false,
        val notSent: Boolean = false,
    ) : Composed
}

/**
 * AI에 글을 청하고 올리기 전에 검사한다(012 research R15, R16). 공급자에 보내는 것은 소재, 댓글 말투, 친구의 말씨뿐이다
 * (FR-027). 회원의 글과 닉네임을 읽지도 않는다. AI의 답은 로그에 남기지 않는다.
 */
@Component
class PostComposer(
    private val writer: CompanionPostWriter,
    private val gate: PostGate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compose(
        attempt: PostAttempt,
        voice: Voice,
    ): Composed {
        val key = "COMPANION_POST:${attempt.id}"
        return when (val written = write(key, attempt, voice)) {
            is Composed.Failed -> written
            is Composed.Written -> gate.rejectionOf(key, written.content) ?: written
        }
    }

    @Suppress("TooGenericExceptionCaught") // 글쓰기의 어떤 실패도 재시도 일정으로 넘긴다(constitution V)
    private fun write(
        key: String,
        attempt: PostAttempt,
        voice: Voice,
    ): Composed {
        // 소재 목록에서 빠진 열쇠다. 다시 청해도 같으므로 검사에 걸린 것으로 센다
        val topic = PostTopics.find(attempt.topic) ?: return Composed.Failed(UNKNOWN_TOPIC, rejected = true)
        val input =
            CompanionPostInput(
                topic = topic.description,
                tone = CompanionTone.valueOf(attempt.commentTone),
                voice = CompanionVoice.valueOf(voice.name),
            )
        return try {
            Composed.Written(writer.write(key, input))
        } catch (e: CompanionPostFailed) {
            failed(e.kind)
        } catch (e: RuntimeException) {
            log.warn("AI 친구의 글쓰기가 예상하지 못한 예외를 던졌습니다: target={}, cause={}", key, e.javaClass.simpleName)
            failed(Kind.UPSTREAM_ERROR)
        }
    }

    private fun failed(kind: Kind): Composed.Failed =
        Composed.Failed(kind.name, rejected = kind == Kind.INVALID_RESPONSE, notSent = kind == Kind.CIRCUIT_OPEN)

    private companion object {
        const val UNKNOWN_TOPIC = "UNKNOWN_TOPIC"
    }
}

/**
 * AI가 쓴 글을 올리기 전에 보는 검사(012 research R16, FR-026). 댓글의 검사([CommentGate])보다 엄격하다. AI 위험 분류가
 * 실패하면 키워드 판정만으로 올리지 않고 다시 시도한다. 글은 누구나 보는 자리에 가고, 버려도 잃는 것이 없다. 걸린 글은
 * 한 번도 보이지 않는다(SC-009). 모양과 규칙은 `ai` 모듈의 검증기가 이미 봤다.
 */
@Component
class PostGate(
    private val contentMask: ContentMask,
    private val riskApi: RiskApi,
    private val riskClassifier: RiskClassifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 걸렸으면 그 실패, 통과하면 null이다. 싼 검사부터 본다. */
    fun rejectionOf(
        key: String,
        content: String,
    ): Composed.Failed? =
        when {
            // 다른 회원에게 가려져 보일 낱말이 있다
            contentMask.mask(content) != content -> Composed.Failed(PROFANITY, rejected = true)
            riskApi.keywordLevel(content) != RiskLevel.NONE -> Composed.Failed(KEYWORD_RISK, rejected = true)
            else -> classified(key, content)
        }

    @Suppress("TooGenericExceptionCaught") // 분류기의 어떤 실패에도 올리지 않는다
    private fun classified(
        key: String,
        content: String,
    ): Composed.Failed? =
        try {
            val risky = riskClassifier.classify(key, content) != ClassifiedRisk.NONE
            if (risky) Composed.Failed(AI_RISK, rejected = true) else null
        } catch (e: RuntimeException) {
            log.warn("AI 친구의 글을 AI로 분류하지 못해 올리지 않습니다: target={}, cause={}", key, e.javaClass.simpleName)
            Composed.Failed(RISK_UNCLASSIFIED)
        }

    private companion object {
        const val PROFANITY = "PROFANITY"
        const val KEYWORD_RISK = "KEYWORD_RISK"
        const val AI_RISK = "AI_RISK"
        const val RISK_UNCLASSIFIED = "RISK_UNCLASSIFIED"
    }
}
