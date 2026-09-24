package com.ogu

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OguApplicationTests {
    @Test
    fun `애플리케이션 컨텍스트가 로드된다`() {
        // 컨텍스트 로드 자체가 검증 대상
    }
}
