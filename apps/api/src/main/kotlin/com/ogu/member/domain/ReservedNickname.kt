package com.ogu.member.domain

/**
 * AI 표시와 헷갈리는 닉네임(009 US2-AC5, research R3). `AI`만 쓰거나, `AI`로 시작하거나 끝나는데 그 옆 글자가
 * 영문이 아니면 막는다(`오구AI`, `ai오구`, `AI2`). `Kai`, `Aiden`처럼 영어 낱말의 일부인 것은 막지 않는다.
 * AI 친구의 닉네임과 같은지는 회원 테이블을 봐야 해서 [MemberRepository.existsAiNicknameKeyWithoutSpaces]가 본다.
 */
object ReservedNickname {
    private const val MARK = "ai"
    private const val SPELLED = "에이아이"

    fun looksLikeAi(nickname: Nickname): Boolean {
        val key = nickname.key
        if (key == MARK || key.contains(SPELLED)) return true
        val leads = key.startsWith(MARK) && !key[MARK.length].isLatinLetter()
        val trails = key.endsWith(MARK) && !key[key.length - MARK.length - 1].isLatinLetter()
        return leads || trails
    }

    private fun Char.isLatinLetter(): Boolean = this in 'a'..'z'
}
