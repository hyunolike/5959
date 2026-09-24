package com.ogu

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.scheduling.annotation.EnableAsync

// 전역 인프라 설정은 루트에 둔다. @ApplicationModuleTest가 개별 모듈만 부트스트랩할 때도
// 루트 애플리케이션 클래스의 설정은 항상 적용되기 때문이다.
@EnableAsync // @ApplicationModuleListener의 비동기 실행에 필요
@EnableJpaAuditing // BaseTimeEntity의 createdAt/updatedAt 자동 기록
@SpringBootApplication
class OguApplication

fun main(args: Array<String>) {
    runApplication<OguApplication>(*args)
}
