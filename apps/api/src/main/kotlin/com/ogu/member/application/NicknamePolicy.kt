package com.ogu.member.application

import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.Nickname
import com.ogu.member.domain.ReservedNickname
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Component

/**
 * 사람이 쓸 수 없는 닉네임(009 US2-AC5, research R3). AI 친구의 닉네임에서 띄어쓰기를 뺀 것, AI 표시와 헷갈리는 것이다.
 * 온보딩, 프로필 수정, 미리 확인이 같은 규칙을 쓴다.
 */
@Component
class NicknamePolicy(
    private val memberRepository: MemberRepository,
) {
    fun isReserved(nickname: Nickname): Boolean =
        ReservedNickname.looksLikeAi(nickname) || memberRepository.existsAiNicknameKeyWithoutSpaces(nickname.key)

    fun rejectIfReserved(nickname: Nickname) {
        if (isReserved(nickname)) throw BusinessException(ErrorCode.NICKNAME_RESERVED)
    }
}
