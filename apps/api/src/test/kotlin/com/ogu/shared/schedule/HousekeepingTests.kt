package com.ogu.shared.schedule

import com.ogu.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.Scheduled

/**
 * 018 T011(research R3): 정리 여섯 개는 04:00(한국 시간) 한 번에 차례로 돈다. 단계는 서로 따로 실패하고, 끝에 타이머를
 * 다시 걸고 이벤트를 한 번 다시 보낸다. 각 정리의 지우는 기준은 모듈별 정리 테스트가 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HousekeepingTests {
    @Autowired
    lateinit var housekeeping: Housekeeping

    @Test
    fun `US4-AC6 04시 한국 시간 한 번에 여섯 정리를 예전 시각 순서대로 돈다`() {
        val scheduled =
            Housekeeping::class.java.methods
                .mapNotNull { it.getAnnotation(Scheduled::class.java) }
                .single()

        assertThat(scheduled.cron).isEqualTo("\${ogu.housekeeping.cron:0 0 4 * * *}")
        assertThat(scheduled.zone).isEqualTo("Asia/Seoul")
        assertThat(housekeeping.stepNames).containsExactly(
            "알림과 끝난 이벤트 발행 정리",
            "로그인 실패 기록 정리",
            "안전 기록 정리",
            "알림 연결 표 정리",
            "주간 리포트 정리",
            "비밀번호 재설정 토큰 정리",
        )
    }

    @Test
    fun `US4-AC6 실제 정리를 한 번에 돌려도 실패 없이 끝난다`() {
        housekeeping.run()
    }
}
