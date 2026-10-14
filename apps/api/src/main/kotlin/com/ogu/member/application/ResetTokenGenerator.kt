package com.ogu.member.application

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** 비밀번호 재설정 링크의 값을 만든다(015 research R2). */
fun interface ResetTokenGenerator {
    /** [email]은 링크를 받을 회원의 이메일이다. 운영의 구현은 쓰지 않는다. */
    fun generate(email: String): String
}

/** `SecureRandom` 32바이트를 base64url로 적은 값. 추측할 수 없다. */
@Component
@Profile("!e2e")
class RandomResetTokenGenerator : ResetTokenGenerator {
    override fun generate(email: String): String = RefreshTokens.generate()
}

/**
 * `e2e` 프로필에서는 값이 이메일에서 정해진다: `e2e-` 뒤에 `sha256("reset:" + 이메일)`의 16진수. 메일함 없이 e2e가 링크를
 * 안다. 가짜 소셜 로그인이 코드에서 사용자를 만드는 것과 같다. 이메일만 알면 비밀번호를 바꿀 수 있으므로 운영에서
 * 켜지면 안 된다. `prod`와 함께 켤 수 없다(`E2eProfileGuard`, `ProdAuthSettingsCheck`).
 */
@Component
@Profile("e2e")
class E2eResetTokenGenerator : ResetTokenGenerator {
    override fun generate(email: String): String = "e2e-" + RefreshTokens.hash("reset:$email")
}
