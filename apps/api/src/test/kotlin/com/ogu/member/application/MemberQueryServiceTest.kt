package com.ogu.member.application

import com.ogu.TestcontainersConfiguration
import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.member.MemberApi
import com.ogu.member.MemberInfo
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.OAuthProvider
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * T012: 피드가 작성자 여러 명을 한 번에 읽는 `MemberApi.getMembers`. N+1을 막으려고 쿼리 한 번으로 끝나야 한다.
 */
@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@Import(TestcontainersConfiguration::class)
class MemberQueryServiceTest {
    @Autowired
    lateinit var memberApi: MemberApi

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    private val statistics by lazy { entityManagerFactory.unwrap(SessionFactory::class.java).statistics }

    @Test
    fun `여러 회원을 쿼리 한 번으로 읽어 ID별로 돌려준다`() {
        val first = saveOnboardedMember(JobRole.DEVELOPMENT, CareerYear.YEAR_3)
        val second = saveOnboardedMember(JobRole.DESIGN, CareerYear.NEWCOMER)
        val notOnboarded = memberRepository.save(Member.registerWithOAuth(OAuthProvider.KAKAO, email = null))
        statistics.clear()

        val members = memberApi.getMembers(listOf(first.id, second.id, notOnboarded.id))

        assertThat(statistics.prepareStatementCount).isEqualTo(1)
        assertThat(members).containsOnlyKeys(first.id, second.id, notOnboarded.id)
        assertThat(members[first.id]).isEqualTo(
            MemberInfo(first.id, first.nickname, JobRole.DEVELOPMENT, CareerYear.YEAR_3),
        )
        assertThat(members[second.id]!!.jobRole).isEqualTo(JobRole.DESIGN)
        assertThat(members[notOnboarded.id]!!.nickname).isNull()
    }

    @Test
    fun `없는 회원 ID는 결과에서 빠지고 같은 ID가 겹쳐도 한 번만 담긴다`() {
        val member = saveOnboardedMember(JobRole.HR, CareerYear.YEAR_1)

        val members = memberApi.getMembers(listOf(member.id, member.id, Long.MAX_VALUE))

        assertThat(members).containsOnlyKeys(member.id)
    }

    @Test
    fun `빈 ID 목록이면 쿼리 없이 빈 결과를 돌려준다`() {
        statistics.clear()

        val members = memberApi.getMembers(emptyList())

        assertThat(members).isEmpty()
        assertThat(statistics.prepareStatementCount).isZero()
    }

    private fun saveOnboardedMember(
        jobRole: JobRole,
        careerYear: CareerYear,
    ): Member {
        val member = Member.registerWithOAuth(OAuthProvider.GOOGLE, email = "${UUID.randomUUID()}@ogu.dev")
        member.completeOnboarding(randomNickname(), jobRole, careerYear, Instant.now())
        return memberRepository.save(member)
    }

    private fun randomNickname(): String =
        "n" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(9)
}
