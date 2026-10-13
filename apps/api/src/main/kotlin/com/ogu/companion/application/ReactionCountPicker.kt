package com.ogu.companion.application

import com.ogu.post.PostCompanionApi
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.Random
import java.util.random.RandomGenerator

/**
 * 한 글에 반응할 AI 친구의 수를 정한다(012 research R2). 글의 반응 기록이 생길 때 한 번 부르고, 돌려준 수는 기록에 남아
 * 바뀌지 않는다. 글쓴이를 뺀 친구의 수로 낮추는 일은 부르는 쪽([ReactionStore.enroll])이 한다.
 */
fun interface ReactionCountPicker {
    fun pick(postId: Long): Int
}

/** 무게로 수를 뽑는 계산. 무작위를 받아 쓰므로 씨앗을 고정해 비율을 잴 수 있다. */
object ReactionCount {
    /** 한 글에 반응할 수 있는 AI 친구의 가장 큰 수. `companion_reaction_target_count_check`와 같다. */
    const val MAX = 3

    /**
     * [weights]는 차례대로 1명, 2명, 3명의 무게다. 합이 100이 아니어도 비례로 뽑는다. 비었거나, 음수가 섞였거나, 모두
     * 0이면 1명이다(012 FR-001). [MAX]를 넘는 자리의 무게는 보지 않는다.
     */
    fun pick(
        weights: List<Int>,
        random: RandomGenerator,
    ): Int {
        val usable = weights.take(MAX)
        val total = usable.sumOf { it.toLong() }
        if (usable.any { it < 0 } || total <= 0) return 1
        val roll = random.nextLong(total)
        // 무게를 차례로 쌓아, 뽑은 값이 처음으로 그 아래에 드는 자리가 수다
        val upTo = usable.runningFold(0L) { sum, weight -> sum + weight }.drop(1)
        return upTo.indexOfFirst { roll < it } + 1
    }
}

/** 운영의 구현. 설정의 무게(`ogu.companion.count-weights`)로 무작위로 뽑는다. */
@Component
@Profile("!e2e")
class WeightedReactionCountPicker(
    private val properties: CompanionProperties,
) : ReactionCountPicker {
    // 운영 이미지의 JRE(eclipse-temurin의 jre)에는 `RandomGenerator.getDefault()`의 알고리즘이 든 jdk.random 모듈이 없다
    private val random: RandomGenerator = Random()

    override fun pick(postId: Long): Int = ReactionCount.pick(properties.countWeights, random)
}

/**
 * `e2e` 프로필의 구현. 무작위로 뽑지 않고 본문의 `[AI친구:2]`, `[AI친구:3]`으로 정한다. 표지가 없으면 한 명이다. 무작위면
 * 이미 있는 e2e 시나리오의 댓글 수, 몬스터 HP, 알림 수가 흔들린다. 가짜 분석기들이 본문의 표지로 결과를 정하는 것과 같은
 * 방식이다(009 research R11).
 */
@Component
@Profile("e2e")
class MarkerReactionCountPicker(
    private val postApi: PostCompanionApi,
) : ReactionCountPicker {
    override fun pick(postId: Long): Int {
        val content = postApi.target(postId, emptyList())?.content ?: return 1
        return MARKER
            .find(content)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 1
    }

    private companion object {
        val MARKER = Regex("\\[AI친구:([1-3])]")
    }
}
