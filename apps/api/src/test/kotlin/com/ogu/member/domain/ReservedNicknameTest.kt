package com.ogu.member.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 009 US2-AC5, research R3: AI 표시와 헷갈리는 닉네임. */
class ReservedNicknameTest {
    @ParameterizedTest
    @ValueSource(strings = ["AI", "ai", "Ai", "오구AI", "오구ai", "AI오구", "ai오구", "AI2", "7ai", "에이아이", "오구에이아이"])
    fun `US2-AC5 AI만 쓰거나 한글과 숫자 옆에 AI가 붙은 닉네임은 AI 표시와 헷갈린다`(nickname: String) {
        assertThat(ReservedNickname.looksLikeAi(Nickname.of(nickname))).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["Kai", "Aiden", "rain", "Thai", "aim", "오구", "다정한오구", "아이", "a1i", "air7"])
    fun `US2-AC5 영어 낱말의 일부로 든 ai와 평범한 닉네임은 막지 않는다`(nickname: String) {
        assertThat(ReservedNickname.looksLikeAi(Nickname.of(nickname))).isFalse()
    }
}
