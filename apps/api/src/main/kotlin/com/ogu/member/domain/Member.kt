package com.ogu.member.domain

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.shared.domain.BaseTimeEntity
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.Locale

/**
 * 회원. 컬럼과 제약은 data-model.md `member` 표를 따른다.
 * 가입 직후에는 온보딩 전(`onboarded_at IS NULL`)이고, [completeOnboarding]은 한 번만 할 수 있다.
 */
@Entity
@Table(name = "member")
class Member private constructor(
    authMethod: AuthMethod,
    email: String?,
    passwordHash: String?,
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0L
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false, updatable = false, length = 10)
    var authMethod: AuthMethod = authMethod
        protected set

    @Column(name = "email", length = 254)
    var email: String? = email
        protected set

    @Column(name = "password_hash", length = 100)
    var passwordHash: String? = passwordHash
        protected set

    @Column(name = "nickname", length = 10)
    var nickname: String? = null
        protected set

    @Column(name = "nickname_key", unique = true, length = 10)
    var nicknameKey: String? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "job_role", length = 20)
    var jobRole: JobRole? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "career_year", length = 20)
    var careerYear: CareerYear? = null
        protected set

    @Column(name = "onboarded_at")
    var onboardedAt: Instant? = null
        protected set

    /** 운영자 여부(005 research R10). 애플리케이션은 바꾸지 않는다. 지정은 운영 절차(SQL)로 한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, insertable = false, updatable = false, length = 10)
    var role: MemberRole = MemberRole.MEMBER
        protected set

    val isOnboarded: Boolean
        get() = onboardedAt != null

    /** 닉네임, 직군, 경력을 저장하고 온보딩을 마친다. 이미 마친 회원이면 `ALREADY_ONBOARDED`. */
    fun completeOnboarding(
        nickname: String,
        jobRole: JobRole,
        careerYear: CareerYear,
        now: Instant,
    ) {
        if (isOnboarded) throw BusinessException(ErrorCode.ALREADY_ONBOARDED)
        val validated = Nickname.of(nickname)
        this.nickname = validated.value
        this.nicknameKey = validated.key
        this.jobRole = jobRole
        this.careerYear = careerYear
        this.onboardedAt = now
    }

    /**
     * 온보딩을 마친 회원의 프로필을 고친다(004 US5). null인 항목은 그대로 둔다. 온보딩 전이면 `ONBOARDING_REQUIRED`다
     * (프로필 수정이 온보딩을 건너뛰는 길이 되지 않게 한다, 004 research R13).
     */
    fun updateProfile(
        nickname: Nickname?,
        jobRole: JobRole?,
        careerYear: CareerYear?,
    ) {
        if (!isOnboarded) throw BusinessException(ErrorCode.ONBOARDING_REQUIRED)
        if (nickname != null) {
            this.nickname = nickname.value
            this.nicknameKey = nickname.key
        }
        if (jobRole != null) this.jobRole = jobRole
        if (careerYear != null) this.careerYear = careerYear
    }

    companion object {
        /** 이메일 정규화(research R8): 앞뒤 공백을 빼고 소문자로 바꾼다. 저장과 비교에 모두 쓴다. */
        fun normalizeEmail(raw: String): String = raw.trim().lowercase(Locale.ROOT)

        /** 이메일 가입. [passwordHash]는 `{bcrypt}...` 형식의 해시다. */
        fun registerWithEmail(
            email: String,
            passwordHash: String,
        ): Member = Member(AuthMethod.EMAIL, normalizeEmail(email), passwordHash)

        /**
         * 외부 계정으로 처음 로그인할 때의 가입. [email]은 제공자가 검증한 이메일만 넘긴다(카카오는 없을 수 있다).
         * 비밀번호는 없다.
         */
        fun registerWithOAuth(
            provider: OAuthProvider,
            email: String?,
        ): Member = Member(provider.authMethod, email?.let(::normalizeEmail), passwordHash = null)
    }
}
