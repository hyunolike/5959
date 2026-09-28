package com.ogu.member.domain

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class MemberTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")

    @Test
    fun `이메일은 앞뒤 공백을 빼고 소문자로 정규화한다`() {
        assertThat(Member.normalizeEmail("  User.Name@Example.COM \t")).isEqualTo("user.name@example.com")
    }

    @Test
    fun `이메일로 가입한 회원은 정규화된 이메일과 비밀번호 해시를 갖고 온보딩 전이다`() {
        val member = Member.registerWithEmail(" Ogu@Example.com ", "{bcrypt}hash")

        assertThat(member.authMethod).isEqualTo(AuthMethod.EMAIL)
        assertThat(member.email).isEqualTo("ogu@example.com")
        assertThat(member.passwordHash).isEqualTo("{bcrypt}hash")
        assertThat(member.nickname).isNull()
        assertThat(member.nicknameKey).isNull()
        assertThat(member.onboardedAt).isNull()
        assertThat(member.isOnboarded).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["오구", "Ogu", "ogu123", "가나다라마바사아자차", "ABCDEFGHIJ", "1", "  오구Ogu1  "])
    fun `닉네임은 앞뒤 공백을 뺀 뒤 한글, 영문, 숫자 1~10자면 통과한다`(raw: String) {
        assertThat(Nickname.isValid(raw)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "   ", "오구 오구", "ogu!", "ogu_1", "오구😀", "ㄱㄴ", "ㅏ", "가나다라마바사아자차카", "ABCDEFGHIJK", "ｏｇｕ"],
    )
    fun `닉네임에 공백, 특수문자, 이모지, 자모가 있거나 10자를 넘으면 거절한다`(raw: String) {
        assertThat(Nickname.isValid(raw)).isFalse()
        assertThatThrownBy { Nickname.of(raw) }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `닉네임 키는 앞뒤 공백을 뺀 닉네임의 소문자다`() {
        val nickname = Nickname.of("  OguOgu1 ")

        assertThat(nickname.value).isEqualTo("OguOgu1")
        assertThat(nickname.key).isEqualTo("oguogu1")
    }

    @Test
    fun `온보딩을 마치면 입력한 닉네임, 소문자 키, 직군, 경력, 완료 시각이 저장된다`() {
        val member = Member.registerWithEmail("ogu@example.com", "{bcrypt}hash")

        member.completeOnboarding(" OguOgu ", JobRole.DEVELOPMENT, CareerYear.YEAR_3, now)

        assertThat(member.nickname).isEqualTo("OguOgu")
        assertThat(member.nicknameKey).isEqualTo("oguogu")
        assertThat(member.jobRole).isEqualTo(JobRole.DEVELOPMENT)
        assertThat(member.careerYear).isEqualTo(CareerYear.YEAR_3)
        assertThat(member.onboardedAt).isEqualTo(now)
        assertThat(member.isOnboarded).isTrue()
    }

    @Test
    fun `규칙에 맞지 않는 닉네임으로는 온보딩할 수 없다`() {
        val member = Member.registerWithEmail("ogu@example.com", "{bcrypt}hash")

        assertThatThrownBy { member.completeOnboarding("오구 오구", JobRole.DESIGN, CareerYear.NEWCOMER, now) }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST)
        assertThat(member.isOnboarded).isFalse()
    }

    @Test
    fun `온보딩을 두 번 하면 ALREADY_ONBOARDED 예외가 난다`() {
        val member = Member.registerWithEmail("ogu@example.com", "{bcrypt}hash")
        member.completeOnboarding("오구", JobRole.DESIGN, CareerYear.NEWCOMER, now)

        assertThatThrownBy { member.completeOnboarding("다른닉", JobRole.HR, CareerYear.YEAR_1, now.plusSeconds(1)) }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.ALREADY_ONBOARDED)
        assertThat(member.nickname).isEqualTo("오구")
        assertThat(member.onboardedAt).isEqualTo(now)
    }
}
